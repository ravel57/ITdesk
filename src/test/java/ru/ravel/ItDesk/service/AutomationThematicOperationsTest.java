package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.dto.AutomationWorkflowDefinition;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomationThematicOperationsTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AutomationWorkflowValueService values = new AutomationWorkflowValueService(mapper);
    private final AutomationActionExecutor actions = mock(AutomationActionExecutor.class);
    private final AutomationScriptRuntime scripts = new AutomationScriptRuntime(mapper,actions,mock(AppSettingsService.class),values);
    private final Map<Class<?>,Object> dependencies = new HashMap<>(Map.of(ObjectMapper.class,mapper,
            AutomationWorkflowValueService.class,values,AutomationActionExecutor.class,actions,AutomationScriptRuntime.class,scripts));
    private final AutomationOperationService operations = construct(AutomationOperationService.class);
    private final AutomationWorkflowNodeService legacy = construct(AutomationWorkflowNodeService.class);

    @ParameterizedTest
    @CsvSource({"task.addTag,task.addTag,tag,Важно", "task.removeTag,task.removeTag,tag,Важно",
            "task.assignLeastLoaded,task.assignToLeastLoadedMember,lineId,3"})
    void thematicWritesTargetTheChosenTaskAndUseTheExistingAtomicReceipt(String operation,String method,String field,String value) throws Exception {
        var transactions = dependency(AutomationOperationTransactionService.class);
        when(transactions.once(anyString(),anyString(),any())).thenAnswer(c -> ((Supplier<?>)c.getArgument(2)).get());
        var task = Task.builder().id(42L).name("Другая заявка").build();
        when(dependency(TaskRepository.class).findById(42L)).thenReturn(Optional.of(task));
        var event = Event.builder().payload(mapper.readTree("{\"task\":{\"id\":1}}" )).build();
        values.setVariable(event.getPayload(),"chosen",42);
        values.setVariable(event.getPayload(),"argument",field.equals("lineId") ? 3 : value);
        var args = operations.resolveArguments(operation,Map.of("taskId","=var.chosen",field,"=var.argument"),event.getPayload());
        var result = operations.execute(operation,args,new AutomationExecutionContext(null,event),false,"receipt-key",null);
        assertThat(result.path("id").asLong()).isEqualTo(42L);
        var context = org.mockito.ArgumentCaptor.forClass(AutomationExecutionContext.class);
        var action = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(actions).execute(eq(method),action.capture(),context.capture());
        assertThat(context.getValue().getEvent().getPayload().path("task").path("id").asLong()).isEqualTo(42L);
        assertThat(action.getValue().path("args").get(0).asText()).isEqualTo(value);
        verify(transactions).once(eq("receipt-key"),eq(operation),any());
    }

    @Test
    void changingTheAssigneeSupportsExpressionsAndExposesTheResultForSubsequentSteps() throws Exception {
        when(dependency(AutomationOperationTransactionService.class).once(anyString(),anyString(),any())).thenAnswer(c -> ((Supplier<?>)c.getArgument(2)).get());
        when(dependency(TaskRepository.class).findById(42L)).thenReturn(Optional.of(Task.builder().id(42L).executor(User.builder().id(9L).build()).build()));
        var event = Event.builder().payload(mapper.readTree("{\"task\":{\"id\":42},\"client\":{\"id\":5}}" )).build();
        var result = operations.execute("task.update",Map.of("taskId",42,"fields",Map.of("assigneeId",9)),new AutomationExecutionContext(null,event),false,"assignment",null);
        verify(actions).execute(eq("task.assignToUser"),any(),any());
        assertThat(result.path("assigneeId").asLong()).isEqualTo(9L);
    }

    @Test
    void newWriteOperationsDoNotWriteWhenTestingTheGraph() {
        for (String operation : List.of("task.addTag","task.removeTag","task.assignLeastLoaded")) {
            operations.execute(operation,Map.of(),new AutomationExecutionContext(null,Event.builder().build()),true,"preview",null);
        }
        verifyNoInteractions(actions,dependency(TaskRepository.class),dependency(AutomationOperationTransactionService.class));
    }

    @Test
    void legacyCreateTaskEvaluatesDynamicFieldsAndKeepsItsIdVariableContract() throws Exception {
        when(dependency(StatusRepository.class).findByDefaultSelectionTrue()).thenReturn(Optional.of(Status.builder().id(1L).build()));
        when(dependency(PriorityRepository.class).findByName("Срочно")).thenReturn(Optional.of(Priority.builder().id(2L).name("Срочно").build()));
        when(dependency(TaskTypeRepository.class).findById(3L)).thenReturn(Optional.of(TaskType.builder().id(3L).build()));
        when(dependency(ServiceRepository.class).findById(4L)).thenReturn(Optional.of(ru.ravel.ItDesk.model.Service.builder().id(4L).build()));
        when(dependency(SupportLineRepository.class).findById(5L)).thenReturn(Optional.of(SupportLine.builder().id(5L).build()));
        when(dependency(TaskService.class).newTaskForSystem(eq(7L),any())).thenReturn(Task.builder().id(55L).build());
        var event = Event.builder().payload(mapper.readTree("{\"client\":{\"id\":7},\"message\":{\"text\":\"Новая проблема\"}}" )).build();
        values.setVariable(event.getPayload(),"priority","Срочно");
        var config = new LinkedHashMap<String,Object>(Map.of("title","=message.text","description","Текст {{ message.text }}",
                "typeId","=1 + 2","serviceId","=2 + 2","supportLineId","=2 + 3","priorityName","=var.priority","resultVariable","createdTaskId"));
        var node = AutomationWorkflowDefinition.Node.builder().type("CREATE_TASK").config(config).build();
        assertThat(legacy.createTask(node,event,false)).isEqualTo(55L);
        var created = org.mockito.ArgumentCaptor.forClass(Task.class);
        verify(dependency(TaskService.class)).newTaskForSystem(eq(7L),created.capture());
        assertThat(created.getValue().getName()).isEqualTo("Новая проблема");
        assertThat(created.getValue().getDescription()).isEqualTo("Текст Новая проблема");
        assertThat(created.getValue().getService().getId()).isEqualTo(4L);
        assertThat(created.getValue().getSupportLine().getId()).isEqualTo(5L);
        assertThat(values.getVariable(event.getPayload(),"createdTaskId")).isEqualTo(55L);
        assertThat(node.getConfig().get("title")).isEqualTo("=message.text");
    }

    @Test
    void legacyNotificationsResolveTheRecipientAndTextWithoutSendingAnExpressionLiteral() throws Exception {
        var event = Event.builder().payload(mapper.readTree("{\"client\":{\"id\":7},\"message\":{\"text\":\"Помогите\"}}" )).build();
        var node = AutomationWorkflowDefinition.Node.builder().type("NOTIFY")
                .config(Map.of("channel","USER","userId","=2 + 3","text","=message.text")).build();
        legacy.notify(null,node,event,false);
        var action = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(actions).execute(eq("notify.user"),action.capture(),any());
        assertThat(action.getValue().path("args").get(0).asInt()).isEqualTo(5);
        assertThat(action.getValue().path("args").get(1).asText()).isEqualTo("Помогите");
    }

    @Test
    void scriptResultsAreDataAndAreNotRenderedAsAnotherTemplate() throws Exception {
        var event = Event.builder().payload(mapper.readTree("{\"client\":{\"id\":7},\"message\":{\"text\":\"Literal {{ client.id }}\"}}" )).build();
        var node = AutomationWorkflowDefinition.Node.builder().type("NOTIFY")
                .config(Map.of("channel","CLIENT","text","=message.text")).build();
        legacy.notify(null,node,event,false);
        var action = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(actions).execute(eq("client.sendMessage"),action.capture(),any());
        assertThat(action.getValue().path("args").get(0).asText()).isEqualTo("Literal {{ client.id }}");
    }

    private <T> T construct(Class<T> type) { try { var c=type.getConstructors()[0];return type.cast(c.newInstance(Arrays.stream(c.getParameterTypes()).map(this::dependency).toArray())); } catch(Exception e) { throw new IllegalStateException(e); } }
    @SuppressWarnings("unchecked") private <T> T dependency(Class<T> type) { return (T) dependencies.computeIfAbsent(type,key -> mock(type)); }
}
