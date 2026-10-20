package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.model.incident.IncidentSignal;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomationSignalCountTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final IncidentDetectorService detector = mock(IncidentDetectorService.class);
    private final AutomationWorkflowValueService values = new AutomationWorkflowValueService(mapper);
    private final AutomationActionExecutor actions = WorkflowRuleRunner.executor(Map.of(
            IncidentDetectorService.class, detector, AutomationWorkflowValueService.class, values));
    private final AutomationOperationTransactionService transactions = mock(AutomationOperationTransactionService.class);
    private final AutomationOperationService operations = operations();

    AutomationSignalCountTest() throws Exception {}

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"all", "ALL", "GLOBAL", " global ", "organization", " ORGANIZATION "})
    void graphOperationAcceptsCanonicalAndPreviouslySavedScopes(String scope) {
        IncidentSignal signal = IncidentSignal.builder().id(118L).build();
        when(detector.findSignal(118L)).thenReturn(Optional.of(signal));
        when(detector.count(eq(signal), eq(15), anyBoolean())).thenReturn(new IncidentDetectorService.WindowCount(
                List.of(signal), Set.of(1L, 2L), Set.of(10L, 20L), ZonedDateTime.now()));
        when(transactions.read(any())).thenAnswer(invocation -> invocation.<Supplier<JsonNode>>getArgument(0).get());
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("signalId", 118L);
        arguments.put("windowMinutes", 15);
        if (scope != null) arguments.put("scope", scope);

        var output = operations.execute("signal.count", arguments, context(), false, "count", null);

        assertThat(output.path("clients").asInt()).isEqualTo(2);
        assertThat(output.path("windowMinutes").asInt()).isEqualTo(15);
        assertThat(output.path("signalIds").get(0).asLong()).isEqualTo(118L);
        verify(detector).count(signal, 15, scope != null && scope.trim().equalsIgnoreCase("organization"));
    }

    @Test
    void unknownScopeStillFailsInsteadOfBroadeningTheSearch() {
        IncidentSignal signal = IncidentSignal.builder().id(118L).build();
        when(detector.findSignal(118L)).thenReturn(Optional.of(signal));
        var api = actions.new IncidentApi(context());
        assertThatThrownBy(() -> api.countSignals(118L, 15, "planet"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("область 'planet' неизвестна");
        verify(detector, never()).count(any(), anyInt(), anyBoolean());
    }

    @Test
    void catalogAndNewTemplateUseTheCanonicalScope() throws Exception {
        var definition = operations.definition("signal.count");
        assertThat(definition.get("fields").toString()).contains("defaultValue=all");
        try (var input = getClass().getClassLoader().getResourceAsStream("automation/templates/outage.json")) {
            JsonNode graph = mapper.readTree(input);
            boolean found = false;
            for (JsonNode node : graph.path("nodes")) if (node.path("config").path("operation").asText().equals("signal.count")) {
                found = true;
                assertThat(node.path("config").path("arguments").path("scope").asText()).isEqualTo("all");
            }
            assertThat(found).isTrue();
        }
    }

    private AutomationExecutionContext context() {
        return new AutomationExecutionContext(AutomationTrigger.builder().id(1L).build(),
                Event.builder().id(1284L).triggerType(TriggerType.MESSAGE_INCOMING).payload(mapper.createObjectNode()).build());
    }

    private AutomationOperationService operations() {
        try {
            var constructor = AutomationOperationService.class.getConstructors()[0];
            Map<Class<?>, Object> dependencies = Map.of(ObjectMapper.class, mapper,
                    AutomationActionExecutor.class, actions, AutomationOperationTransactionService.class, transactions);
            Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                    .map(type -> dependencies.containsKey(type) ? dependencies.get(type) : mock(type)).toArray();
            return (AutomationOperationService) constructor.newInstance(arguments);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
