package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.internal.database.DatabaseTypeRegister;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.resource.StringResource;
import ru.ravel.ItDesk.component.AfterCommitExecutor;
import ru.ravel.ItDesk.repository.*;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransparentAutomationGraphTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AutomationWorkflowValueService values = new AutomationWorkflowValueService(mapper);
    private final AutomationScriptRuntime expressions = new AutomationScriptRuntime(mapper, mock(AutomationActionExecutor.class), mock(AppSettingsService.class), values);
    private final AutomationOperationService operations = construct(AutomationOperationService.class, Map.of(ObjectMapper.class, mapper, AutomationScriptRuntime.class, expressions, AutomationWorkflowValueService.class, values));
    private final AutomationWorkflowEngine engine = new AutomationWorkflowEngine(mapper, expressions, mock(AutomationWorkflowRunRepository.class), mock(AutomationTriggerRepository.class), mock(AutomationPayloadService.class), values, mock(AutomationWorkflowNodeService.class), operations, mock(AutomationGraphHistoryService.class), mock(AfterCommitExecutor.class));

    @ParameterizedTest
    @ValueSource(strings = {"reply","priority","classification","escalation","recovery","knowledge","smart-create","duplicates","monitoring-start","monitoring-resolve","monitoring-update","outage"})
    void generatedGraphsAreValidAndContainNoOpaquePolicyCalls(String name) throws Exception {
        String json = resource("automation/templates/" + name + ".json");
        assertThat(engine.validateAsMap(json)).containsEntry("valid", true);
        assertThat(json).doesNotContain("jev.createTaskIfNeeded(", "jev.setPriority(", "jev.classifyTask(", "incident.fromMonitoring(", "jev.escalateIfNeeded(", "jev.checkIncidentRecovery(", "jev.suggestKnowledgeArticle(");
        assertThat(mapper.readTree(json).path("nodes").get(1).path("type").asText()).isEqualTo("PARAMETERS");
    }

    @Test
    void graphNormalizationPreservesCustomSectionOrderFromTheEditor() throws Exception {
        ObjectNode graph = (ObjectNode) mapper.readTree(resource("automation/templates/priority.json"));
        var sections = graph.putArray("sectionOrder").add("3. Выбор приоритета").add("1. Настройки");
        String normalized = engine.normalize(graph.toString());
        JsonNode saved = mapper.readTree(normalized);
        assertThat(saved.path("sectionOrder")).isEqualTo(sections);
        assertThat(saved.path("entryNodeId")).isEqualTo(graph.path("entryNodeId"));
        assertThat(saved.path("edges")).isEqualTo(graph.path("edges"));
        assertThat(mapper.readTree(engine.normalize(normalized)).path("sectionOrder")).isEqualTo(sections);
        assertThat(engine.validateAsMap(normalized)).containsEntry("valid", true);
        assertThat(engine.triggerType(normalized).name()).isEqualTo("TASK_CREATED");
    }

    @Test
    void graphWithoutSectionOrderRemainsCompatible() throws Exception {
        String original = resource("automation/templates/reply.json");
        assertThat(mapper.readTree(original).has("sectionOrder")).isFalse();
        assertThat(mapper.readTree(engine.normalize(original)).path("sectionOrder").isArray()).isTrue();
        assertThat(mapper.readTree(engine.normalize(original)).path("sectionOrder")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"V64__transparent_automation_graphs.sql", "V67__knowledge_hints_latest_message.sql", "V68__current_automation_configuration.sql"})
    void forwardMigrationParsesWithPlaceholderReplacementEnabled(String migration) throws Exception {
        var config = Flyway.configure();
        var parser = DatabaseTypeRegister.getDatabaseTypeForUrl("jdbc:postgresql://localhost/unused", config).createParser(config, null, new ParsingContext());
        int count = 0;
        try (var statements = parser.parse(new StringResource(resource("db/migration/" + migration)))) {
            while (statements.hasNext()) { statements.next(); count++; }
        }
        assertThat(count).isGreaterThanOrEqualTo(migration.startsWith("V64") ? 25 : 1);
    }

    @Test
    void currentConfigurationSnapshotContainsValidGraphs() throws Exception {
        String sql = resource("db/migration/V68__current_automation_configuration.sql");
        int start = sql.indexOf("$snapshot$\n") + "$snapshot$\n".length();
        JsonNode snapshot = mapper.readTree(sql.substring(start, sql.indexOf("\n$snapshot$", start)));
        assertThat(snapshot.path("triggers")).hasSize(18);
        for (JsonNode trigger : snapshot.path("triggers")) {
            assertThat(engine.validateAsMap(trigger.path("workflow_definition").toString()))
                    .as(trigger.path("name").asText()).containsEntry("valid", true);
        }
    }

    @Test
    void unifiedValuesSupportArithmeticCollectionsAndTemplates() throws Exception {
        JsonNode payload = mapper.readTree("{\"_automation\":{\"variables\":{\"count\":3,\"items\":[{\"id\":7,\"name\":\"VPN\"}]}}}");
        assertThat(expressions.evaluateValue("var.count * 2 + 1", payload).toString()).isEqualTo("7");
        assertThat(expressions.evaluateValue("var.items.first().id", payload).toString()).isEqualTo("7");
        assertThat(expressions.evaluateValue("'Заявка: ' + var.items.first().name", payload)).isEqualTo("Заявка: VPN");
        assertThat(expressions.renderValueTemplate("{{ var.items.first().name }}", payload)).isEqualTo("VPN");
        assertThat(expressions.evaluateValue("var.items.first().name.truncate(2)", payload)).isEqualTo("VP");
    }

    @Test
    void listFilteringUsesLocalItemAndDoesNotLeakItIntoTheCaller() throws Exception {
        ObjectNode payload = mapper.createObjectNode(); values.setVariable(payload,"item","parent");
        var ctx = context(payload);
        var output = operations.execute("list.select",Map.of("items",List.of(Map.of("id",2,"active",false),Map.of("id",1,"active",true)),"where","var.item.active","sortBy","id"),ctx,true,"test",null);
        assertThat(output.size()).isEqualTo(1); assertThat(output.get(0).path("id").asInt()).isEqualTo(1);
        assertThat(values.getVariable(payload,"item")).isEqualTo("parent");
    }

    @Test
    void perItemScriptsAreResolvedInsideTheListAndSupportComputedKeys() {
        ObjectNode payload = mapper.createObjectNode();
        values.setVariable(payload, "item", Map.of("score", 999));
        values.setVariable(payload, "minimum", 1);
        List<Map<String, Object>> items = List.of(Map.of("id", 1, "score", 2, "group", "a"),
                Map.of("id", 2, "score", 4, "group", "b"), Map.of("id", 3, "score", 3, "group", "b"));
        values.setVariable(payload, "source", items);
        Map<String, Object> arguments = operations.resolveArguments("list.select", Map.of(
                "items", "=var.source", "where", "var.item.score > var.minimum",
                "sortBy", "var.item.score * -1", "distinctBy", "var.item.group"), payload);
        assertThat(arguments.get("sortBy")).isEqualTo("var.item.score * -1");
        var output = operations.execute("list.select", arguments, context(payload), true, "test", null);
        assertThat(output.findValuesAsText("id")).containsExactly("2", "1");
        assertThat(values.getVariable(payload, "item").toString()).contains("999");
        operations.validateArguments("list.select", Map.of("sortBy", "var.item.score * -1"));
        assertThatThrownBy(() -> operations.validateArguments("list.select", Map.of("sortBy", "var.item.score @")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyItemPathsAreCompatibleAndScriptPrefixesAreNotEvaluatedEarly() {
        ObjectNode payload = mapper.createObjectNode();
        var args = operations.resolveArguments("list.select", Map.of("sortBy", "createdAt", "distinctBy", "service.id",
                "where", "=var.item.active"), payload);
        assertThat(args).containsEntry("sortBy", "createdAt").containsEntry("distinctBy", "service.id")
                .containsEntry("where", "var.item.active");
        var projected = operations.resolveArguments("list.project", Map.of("items", List.of(Map.of("id", 7)),
                "expression", "=var.item.id"), payload);
        assertThat(operations.execute("list.project", projected, context(payload), true, "test", null).get(0).asInt()).isEqualTo(7);
    }

    @Test
    void analysisInSimulationRequiresAnExplicitTestResultAndNeverCallsJev() {
        ObjectNode payload = mapper.createObjectNode();
        assertThatThrownBy(() -> operations.execute("ai.decide", Map.of(), context(payload), true,"test",null)).hasMessageContaining("тестовый ответ");
        var result = operations.execute("ai.decide",Map.of(),context(payload),true,"test",Map.of("available",true,"probability",0.9));
        assertThat(result.path("probability").asDouble()).isEqualTo(0.9);
    }

    @Test
    void readStepsCanUseTestDataWithoutQueryingTheDatabase() {
        var result=operations.execute("data.tasks",Map.of(),context(mapper.createObjectNode()),true,"test",List.of(Map.of("id",7,"name","Пример")));
        assertThat(result.get(0).path("id").asInt()).isEqualTo(7);
        verifyNoInteractions(field(operations,TaskRepository.class),field(operations,ClientRepository.class));
    }

    @Test
    void loopSimulationCollectsEveryItemAndRestoresTheOuterVariable() throws Exception {
        String json = """
        {"version":3,"entryNodeId":"e","nodes":[
        {"id":"e","type":"EVENT","config":{"triggerType":"TASK_CREATED"}},
        {"id":"loop","type":"FOR_EACH","config":{"itemsExpression":"[1,2,3]","itemVariable":"item","endNodeId":"endIteration","collectExpression":"var.doubled","resultVariable":"results"}},
        {"id":"calc","type":"SET_VARIABLE","config":{"name":"doubled","expression":"var.item * 2"}},
        {"id":"endIteration","type":"LOOP_END","config":{}},
        {"id":"done","type":"END","config":{}}],"edges":[
        {"id":"1","source":"e","target":"loop","sourceHandle":"next"},
        {"id":"2","source":"loop","target":"calc","sourceHandle":"body"},
        {"id":"3","source":"loop","target":"done","sourceHandle":"next"},
        {"id":"4","source":"calc","target":"endIteration","sourceHandle":"next"}]}
        """;
        ObjectNode payload=mapper.createObjectNode();values.setVariable(payload,"item","parent");
        var result=engine.test(json,payload);
        assertThat(result).containsEntry("valid",true).containsEntry("completed",true);
        JsonNode out=(JsonNode)result.get("payload");
        assertThat(out.path("_automation").path("variables").path("results").toString()).isEqualTo("[2,4,6]");
        assertThat(values.getVariable(out,"item")).isEqualTo("parent");
    }

    @Test
    void inlineSubflowsUseExplicitInputsOutputsAndLocalVariables() throws Exception {
        String child="""
        {"entryNodeId":"event","nodes":[{"id":"event","type":"EVENT","config":{"triggerType":"TASK_CREATED"}},{"id":"calc","type":"SET_VARIABLE","config":{"name":"private","expression":"var.input * 2"}},{"id":"end","type":"END"}],"edges":[{"id":"a","source":"event","target":"calc","sourceHandle":"next"},{"id":"b","source":"calc","target":"end","sourceHandle":"next"}]}
        """;
        when(field(engine,AutomationTriggerRepository.class).findById(2L)).thenReturn(Optional.of(ru.ravel.ItDesk.model.AutomationTrigger.builder().id(2L).workflowDefinition(child).build()));
        String parent="""
        {"entryNodeId":"event","nodes":[{"id":"event","type":"EVENT","config":{"triggerType":"TASK_CREATED"}},{"id":"child","type":"SUBFLOW","config":{"triggerId":2,"mode":"INLINE","localVariables":true,"inputs":{"input":"=3"},"outputs":{"answer":"=var.private"}}},{"id":"end","type":"END"}],"edges":[{"id":"a","source":"event","target":"child","sourceHandle":"next"},{"id":"b","source":"child","target":"end","sourceHandle":"success"},{"id":"c","source":"child","target":"end","sourceHandle":"error"}]}
        """;
        ObjectNode payload=mapper.createObjectNode();values.setVariable(payload,"private",99);
        var result=engine.test(parent,payload);assertThat(result).containsEntry("valid",true).containsEntry("completed",true);
        var variables=((JsonNode)result.get("payload")).path("_automation").path("variables");
        assertThat(variables.path("answer").asInt()).isEqualTo(6);assertThat(variables.path("private").asInt()).isEqualTo(99);
        assertThat(variables.has("input")).isFalse();
    }

    @Test
    void queuedOperationsResumeTheOriginalDefinitionAndPreserveTheirResult() throws Exception {
        var fake=mock(AutomationOperationService.class);when(fake.definition(anyString())).thenReturn(Map.of("effect","ANALYSIS"));
        when(fake.resolve(any(),any())).thenReturn(Map.of());when(fake.execute(anyString(),any(),any(),anyBoolean(),anyString(),any())).thenReturn(mapper.createObjectNode().put("text","готово"));
        var repository=mock(AutomationWorkflowRunRepository.class);
        var queuedEngine=new AutomationWorkflowEngine(mapper,expressions,repository,mock(AutomationTriggerRepository.class),mock(AutomationPayloadService.class),values,mock(AutomationWorkflowNodeService.class),fake,mock(AutomationGraphHistoryService.class),mock(AfterCommitExecutor.class));
        String graph="""
        {"entryNodeId":"e","nodes":[{"id":"e","type":"EVENT","config":{"triggerType":"TASK_CREATED"}},{"id":"op","type":"OPERATION","config":{"operation":"ai.generate","background":true,"arguments":{},"resultVariable":"result"}},{"id":"end","type":"END"}],"edges":[{"id":"a","source":"e","target":"op","sourceHandle":"next"},{"id":"b","source":"op","target":"end","sourceHandle":"success"},{"id":"c","source":"op","target":"end","sourceHandle":"error"}]}
        """;
        var trigger=ru.ravel.ItDesk.model.AutomationTrigger.builder().id(1L).workflowDefinition(graph).build();
        var event=ru.ravel.ItDesk.model.Event.builder().id(10L).payload(mapper.createObjectNode()).build();
        assertThat(queuedEngine.execute(trigger,event).scheduled()).isTrue();
        var saved=org.mockito.ArgumentCaptor.forClass(ru.ravel.ItDesk.model.AutomationWorkflowRun.class);verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEventPayload().path("_automation").has("definitions")).isFalse();
        trigger.setWorkflowDefinition(graph.replace("ai.generate","changed.after.queue"));
        assertThat(queuedEngine.resume(trigger,saved.getValue()).completed()).isTrue();
        verify(fake).execute(eq("ai.generate"),any(),any(),eq(false),anyString(),any());
    }

    @Test
    void variableFieldSuggestionsIncludeTypedResultPaths() {
        var metadata=new AutomationExpressionMetadataService(mapper);
        var request=ru.ravel.ItDesk.dto.AutomationExpressionSuggestionRequest.builder().text("var.decision.").cursor("var.decision.".length()).variables(List.of("decision","decision.available","decision.probability","decision.value.id")).build();
        String suggestions=metadata.suggest(request).toString();
        assertThat(suggestions).contains("available","probability","value");
    }

    private String simulationVariant = "normal";

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"priority,lowConfidence", "priority,unavailable", "classification,lowConfidence", "knowledge,unavailable", "reply,uncertain", "recovery,unrelated", "monitoring-start,lowSeverity", "monitoring-update,lowSeverity", "monitoring-resolve,stillOpen", "escalation,tooEarly"})
    void protectiveBranchesSuppressWritesOrChooseTheExplicitAlternative(String graph,String variant) throws Exception {
        simulationVariant=variant; everyDefaultGraphCanBeSimulatedThroughItsBusinessBranch(graph);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reply","priority","classification","escalation","recovery","knowledge","smart-create","duplicates","monitoring-start","monitoring-resolve","monitoring-update","outage"})
    void everyDefaultGraphCanBeSimulatedThroughItsBusinessBranch(String name) throws Exception {
        var transaction = field(operations, AutomationOperationTransactionService.class);
        when(transaction.read(any())).thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(0)).get());
        var owner=ru.ravel.ItDesk.model.User.builder().id(1L).username("owner").authorities(List.of(ru.ravel.ItDesk.model.Role.ADMIN)).build();
        var firstLine=ru.ravel.ItDesk.model.SupportLine.builder().id(1L).name("Первая").level(1).active(true).responsible(owner).members(List.of(owner)).build();
        var nextLine=ru.ravel.ItDesk.model.SupportLine.builder().id(2L).name("Вторая").level(2).active(true).members(List.of()).build();
        var service=ru.ravel.ItDesk.model.Service.builder().id(1L).code("vpn").name("VPN").owner(owner).executors(new LinkedHashSet<>(List.of(owner))).defaultSupportLine(firstLine).build();
        var task=ru.ravel.ItDesk.model.Task.builder().id(100L).name("VPN не работает").service(service).supportLine(firstLine).enteredCurrentLineAt(java.time.ZonedDateTime.now().minusHours(2)).createdAt(java.time.ZonedDateTime.now().minusHours(3)).build();
        var candidate=ru.ravel.ItDesk.model.Task.builder().id(111L).name("VPN недоступен").service(service).createdAt(java.time.ZonedDateTime.now()).build();
        var client=ru.ravel.ItDesk.model.Client.builder().id(7L).tasks(List.of(task)).messages(List.of()).build();
        if(name.equals("knowledge")) client.setMessages(List.of(ru.ravel.ItDesk.model.Message.builder().id(200L).text("VPN снова не работает, помогите").build()));
        var incident=ru.ravel.ItDesk.model.incident.SupportIncident.builder().id(5L).incidentNumber("INC-5").title("Сбой VPN").status(ru.ravel.ItDesk.model.incident.IncidentStatus.MONITORING).services(new LinkedHashSet<>(List.of(service))).build();
        var link=ru.ravel.ItDesk.model.incident.IncidentTaskLink.builder().id(3L).task(candidate).incident(incident).active(true).build();
        when(field(operations, ClientRepository.class).findById(7L)).thenReturn(Optional.of(client));
        when(field(operations, ClientRepository.class).findByTaskId(anyLong())).thenAnswer(call -> Optional.of(call.<Long>getArgument(0)==111L?ru.ravel.ItDesk.model.Client.builder().id(8L).build():client));
        when(field(operations, TaskRepository.class).findOpenCreatedSince(any(),any())).thenReturn(List.of(candidate));
        when(field(operations, TaskRepository.class).findById(100L)).thenReturn(Optional.of(task));
        when(field(operations, ServiceRepository.class).findById(1L)).thenReturn(Optional.of(service));
        when(field(operations, ServiceRepository.class).findAllByActiveTrueOrderByOrderNumberAscNameAsc()).thenReturn(List.of(service));
        when(field(operations, ServiceCatalogService.class).getAvailableServices(any())).thenReturn(List.of(Map.of("id",1L)));
        when(field(operations, SupportLineRepository.class).findAll()).thenReturn(List.of(firstLine,nextLine));
        when(field(operations, TaskTypeRepository.class).findAll()).thenReturn(List.of(ru.ravel.ItDesk.model.TaskType.builder().id(2L).type("Проблема").build()));
        when(field(operations, PriorityRepository.class).findAll()).thenReturn(List.of(ru.ravel.ItDesk.model.Priority.builder().id(1L).name("Срочно").orderNumber(1).critical(true).build(),ru.ravel.ItDesk.model.Priority.builder().id(2L).name("Обычный").orderNumber(2).critical(false).build()));
        when(field(operations, KnowledgeService.class).getKnowledgeBase()).thenReturn(List.of(ru.ravel.ItDesk.model.Knowledge.builder().id(9L).title("VPN").texts(List.of("Перезапустите VPN")).build()));
        when(field(operations, IncidentTaskLinkRepository.class).findFirstByTaskIdAndActiveTrue(111L)).thenReturn(Optional.of(link));
        when(field(operations, IncidentTaskLinkRepository.class).findAll()).thenReturn(List.of(link));
        if(name.equals("recovery")) when(field(operations, IncidentTaskLinkRepository.class).findFirstByTaskIdAndActiveTrue(100L)).thenReturn(Optional.of(ru.ravel.ItDesk.model.incident.IncidentTaskLink.builder().id(4L).task(task).incident(incident).active(true).build()));
        when(field(operations, IncidentRepository.class).findAll()).thenReturn(name.equals("monitoring-resolve")?List.of(incident):List.of());
        when(field(operations, AutomationActionExecutor.class).execute(eq("incident.countSignals"),any(),any())).thenReturn(Map.of("clients",3,"signalIds",List.of(1,2,3),"windowMinutes",15));
        when(field(operations, AutomationActionExecutor.class).execute(eq("incident.checkCooldown"),any(),any())).thenReturn(Map.of("active",false));
        if (simulationVariant.equals("stillOpen")) when(field(operations, MonitoringProblemRepository.class).countByServiceIdAndStatus(anyLong(),any())).thenReturn(2L);
        if (simulationVariant.equals("tooEarly")) task.setEnteredCurrentLineAt(java.time.ZonedDateTime.now().minusMinutes(2));
        String json=resource("automation/templates/"+name+".json");
        ObjectNode payload=(ObjectNode)mapper.readTree("""
        {"task":{"id":100,"name":"VPN не работает","description":"Нет подключения","service":{"id":1}},"client":{"id":7,"organization":{"id":9}},"message":{"id":200,"text":"VPN снова не работает, помогите","date":"2026-10-19T12:00:00Z"},"service":{"id":1,"name":"VPN"},"problem":{"name":"VPN unavailable","severity":5,"status":"OPEN","startedAt":"2026-10-19T12:00:00Z"}}
        """);
        if (simulationVariant.equals("lowSeverity")) ((ObjectNode)payload.path("problem")).put("severity",2);
        ObjectNode samples=payload.with("_automation").putObject("testResults");
        for(JsonNode node:mapper.readTree(json).path("nodes")) {
            JsonNode config=node.path("config");String op=config.path("operation").asText(), variable=config.path("resultVariable").asText();
            Object answer=Map.of("available",true,"probability",0.99,"choice","1","value",Map.of("id",1,"name","VPN","allowedTypeIds",List.of()));
            if(variable.equals("kind")) answer=Map.of("available",true,"probability",0.99,"choice","new_request");
            if(variable.equals("match")) answer=Map.of("available",true,"probability",0.01);
            if(variable.equals("repeat")) answer=Map.of("available",false);
            if(variable.equals("typeDecision")) answer=Map.of("available",true,"probability",0.99,"value",Map.of("id",2,"name","Проблема"));
            if(variable.equals("lineDecision")) answer=Map.of("available",true,"probability",0.99,"value",Map.of("id",2,"name","Вторая"));
            if(name.equals("recovery")) answer=Map.of("available",true,"probability",0.99,"choice","still_broken");
            if(name.equals("knowledge")) answer=Map.of("available",true,"probability",0.99,"value",Map.of("id",9,"name","VPN","text","Перезапустить VPN"));
            if(name.equals("duplicates")) answer=Map.of("available",true,"probability",0.99,"value",Map.of("id",111,"clientId",8,"incident",Map.of("id",5,"number","INC-5","status","MONITORING")));
            if(op.equals("signal.analyze")) answer=Map.of("signalId",1,"probability",0.99,"serviceId",1,"serviceName","VPN","symptom","offline","monitoringStatus","WARNING");
            if(op.equals("signal.verify")) answer=Map.of("sameProblem",0.99,"severity","P1","severityScore",0.99,"cause","сеть");
            if(op.equals("ai.generate")) answer=Map.of("text","Перезапустите VPN");
            if (simulationVariant.equals("lowConfidence")) { var low=mapper.valueToTree(answer); if(low instanceof ObjectNode object) object.put("probability",0.1);answer=low; }
            if (simulationVariant.equals("unavailable")) answer=Map.of("available",false);
            if (simulationVariant.equals("uncertain")) answer=Map.of("available",true,"probability",0.4);
            if (simulationVariant.equals("unrelated")) answer=Map.of("available",true,"probability",0.99,"choice","unrelated");
            if(op.startsWith("ai.")||op.equals("signal.analyze")||op.equals("signal.verify")) samples.set(node.path("id").asText(),mapper.valueToTree(answer));
        }
        var result=engine.test(json,payload);
        assertThat(result).as(name).containsEntry("valid",true).containsEntry("completed",true);
        @SuppressWarnings("unchecked") var trace=(List<AutomationWorkflowEngine.TraceStep>)result.get("trace");
        assertThat(trace).as(name).noneMatch(step -> step.status().equals("FAILED")||step.status().equals("NEEDS_INPUT"));
        if (!simulationVariant.equals("normal")) {
            if (simulationVariant.equals("stillOpen")) {
                assertThat(trace).anyMatch(step->step.detail().equals("incident.note"));
                assertThat(trace).noneMatch(step->step.detail().equals("incident.observe"));
            } else {
                Set<String> writes=new HashSet<>();operations.catalog().stream().filter(op->op.get("effect").equals("WRITE")).forEach(op->writes.add(op.get("id").toString()));
                assertThat(trace).noneMatch(step->writes.contains(step.detail()));
            }
            return;
        }
        String expected=switch(name){case "reply"->"client.replyFlag";case "priority","classification","escalation"->"task.update";case "recovery"->"incident.reportProblem";case "knowledge"->"client.note";case "smart-create"->"task.create";case "duplicates"->"incident.linkTasks";case "monitoring-start","monitoring-update"->"incident.create";case "monitoring-resolve"->"incident.observe";default->"notify.user";};
        assertThat(trace).as(name).anyMatch(step -> expected.equals(step.detail())&&step.status().equals("PREVIEW"));
        if(name.equals("smart-create")) {
            var creates=trace.stream().filter(step -> step.detail().equals("task.create")).toList();
            assertThat(creates).hasSize(2);
            assertThat(creates.get(0).input().path("messageId").asInt()).isEqualTo(200);
            assertThat(creates.get(1).input().path("messageId").isNull()).isTrue();
        }
        if(name.equals("monitoring-start")) assertThat(trace.stream().filter(step->step.detail().equals("incident.create")).findFirst().orElseThrow().input().path("severity").asText()).isEqualTo("P2");
        verifyNoInteractions(field(operations,JevClient.class));
    }

    @SuppressWarnings("unchecked") private static <T> T field(Object target,Class<T> type){try{for(var field:target.getClass().getDeclaredFields())if(field.getType()==type){field.setAccessible(true);return (T)field.get(target);}throw new IllegalArgumentException(type.getName());}catch(Exception e){throw new IllegalStateException(e);}}

    private AutomationExecutionContext context(JsonNode payload) {return new AutomationExecutionContext(ru.ravel.ItDesk.model.AutomationTrigger.builder().id(1L).build(),ru.ravel.ItDesk.model.Event.builder().payload(payload).build());}
    private String resource(String path) throws Exception {try(var in=Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    @SuppressWarnings("unchecked") private static <T> T construct(Class<T> type,Map<Class<?>,Object> known){try{var ctor=type.getConstructors()[0];Object[] args=Arrays.stream(ctor.getParameterTypes()).map(t->known.containsKey(t)?known.get(t):mock(t)).toArray();return (T)ctor.newInstance(args);}catch(Exception e){throw new IllegalStateException(e);}}
}
