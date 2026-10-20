package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.incident.*;
import ru.ravel.ItDesk.repository.*;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Stream;

/** Small domain operations. Selection policy, thresholds, prompts and sequencing belong to the graph. */
@Service
@RequiredArgsConstructor
public class AutomationOperationService {
    private final ObjectMapper mapper;
    private final AutomationScriptRuntime expressions;
    private final AutomationWorkflowValueService values;
    private final AutomationPayloadService payloadService;
    private final AutomationActionExecutor actions;
    private final JevClient jev;
    private final JevDecisionRecorder decisionRecorder;
    private final LlmService llm;
    private final JdbcTemplate jdbc;
    private final AutomationOperationTransactionService transactions;
    private final ClientRepository clients;
    private final TaskRepository tasks;
    private final ServiceRepository services;
    private final ServiceCatalogService catalog;
    private final TaskTypeRepository types;
    private final PriorityRepository priorities;
    private final StatusRepository statuses;
    private final SupportLineRepository lines;
    private final UserRepository users;
    private final KnowledgeService knowledge;
    private final IncidentRepository incidents;
    private final IncidentTaskLinkRepository links;
    private final IncidentService incidentService;
    private final MonitoringProblemRepository monitoring;
    private final OrganizationServiceAssignmentRepository assignments;

    private volatile List<Map<String, Object>> operationCatalog;

    public List<Map<String, Object>> catalog() {
        if (operationCatalog != null) return operationCatalog;
        try (var input = new ClassPathResource("automation/operations.json").getInputStream()) {
            operationCatalog = List.copyOf(mapper.readValue(input, new TypeReference<List<Map<String, Object>>>() {}));
            return operationCatalog;
        } catch (Exception e) { throw new IllegalStateException("Не удалось прочитать каталог операций", e); }
    }

    public Map<String, Object> definition(String id) {
        return catalog().stream().filter(x -> id.equals(x.get("id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Неизвестная операция: " + id));
    }

    /** '=expression' means a value expression; other strings are text, optionally with {{ }} substitutions. */
    public Object resolve(Object input, JsonNode payload) {
        if (input instanceof String text) {
            return text.startsWith("=") ? expressions.evaluateValue(text.substring(1), payload)
                    : expressions.renderValueTemplate(text, payload);
        }
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), resolve(v, payload)));
            return result;
        }
        if (input instanceof Collection<?> list) return list.stream().map(v -> resolve(v, payload)).toList();
        return input;
    }

    /** Per-item scripts stay unevaluated until their operation supplies var.item. */
    public Map<String, Object> resolveArguments(String operation, Object input, JsonNode payload) {
        if (!(input instanceof Map<?, ?> map)) throw new IllegalArgumentException("Параметры операции должны быть объектом");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            String key = entry.getKey().toString();
            Map<String, Object> field = operationField(operation, key);
            result.put(key, "script".equals(field.get("type")) && entry.getValue() instanceof String text
                    ? (Boolean.TRUE.equals(field.get("legacyItemPath")) ? text : itemScript(text, false)) : resolve(entry.getValue(), payload));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> operationField(String operation, String key) {
        return ((List<Map<String, Object>>) definition(operation).get("fields")).stream()
                .filter(field -> key.equals(field.get("key"))).findFirst().orElse(Map.of());
    }

    private String itemScript(String text, boolean legacyPath) {
        String script = text.trim();
        if (script.startsWith("=")) return script.substring(1);
        // Older graphs stored plain item paths (createdAt, service.id), rather than expressions.
        if (legacyPath && script.matches("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*")
                && !script.startsWith("var.") && !Set.of("true", "false", "null").contains(script)) return "var.item." + script;
        return script;
    }

    private JsonNode itemValue(String script, JsonNode item, JsonNode payload) {
        return mapper.valueToTree(expressions.evaluateValue(itemScript(script, true), itemPayload(payload, item)));
    }

    public void validateRule(Object specification) { validateRule(mapper.valueToTree(specification), 0); }
    private void validateRule(JsonNode rule, int depth) {
        if (depth > 20 || !rule.isObject()) throw new IllegalArgumentException("Условие должно быть объектом, вложенность до 20");
        if (rule.has("all") || rule.has("any")) {
            JsonNode group = rule.path(rule.has("all") ? "all" : "any");
            if (!group.isArray() || group.isEmpty() || group.size() > 100) throw new IllegalArgumentException("В группе нужно от 1 до 100 условий");
            group.forEach(child -> validateRule(child, depth + 1)); return;
        }
        if (rule.has("not")) { validateRule(rule.path("not"), depth + 1); return; }
        if (!rule.has("left")) throw new IllegalArgumentException("Не выбрано проверяемое значение");
        String operator = rule.path("operator").asText();
        if (!Set.of("true","=","==","!=",">","<",">=","<=","in","null","notNull","empty","notEmpty").contains(operator)) throw new IllegalArgumentException("Неизвестный оператор сравнения");
        if (!Set.of("true","null","notNull","empty","notEmpty").contains(operator) && !rule.has("right")) throw new IllegalArgumentException("Не задано значение для сравнения");
        validateValue(rule.path("left"), 0); validateValue(rule.path("right"), 0);
    }

    public void validateArguments(String operation, Object arguments) {
        Map<String,Object> spec=definition(operation);
        if (!(arguments instanceof Map<?,?> map)) throw new IllegalArgumentException("Параметры операции должны быть объектом");
        @SuppressWarnings("unchecked") List<Map<String,Object>> fields=(List<Map<String,Object>>)spec.get("fields");
        Set<String> keys=new HashSet<>(); fields.forEach(field -> keys.add(field.get("key").toString()));
        for(Object key:map.keySet()) if (!keys.contains(key.toString())) throw new IllegalArgumentException("Неизвестный параметр операции: "+key);
        for(Map<String,Object> field:fields) if (Boolean.TRUE.equals(field.get("required")) && (!map.containsKey(field.get("key")) || map.get(field.get("key"))==null || map.get(field.get("key")).toString().isBlank())) throw new IllegalArgumentException("Заполните параметр: "+field.get("label"));
        for (Map<String, Object> field : fields) {
            Object value = map.get(field.get("key"));
            if ("script".equals(field.get("type")) && value != null) {
                if (!(value instanceof String)) throw new IllegalArgumentException("Скрипт должен быть строкой: " + field.get("label"));
                String script = itemScript((String) value, Boolean.TRUE.equals(field.get("legacyItemPath")));
                if (!script.isBlank()) expressions.validateValueExpression(script);
            }
        }
        validateValue(mapper.valueToTree(arguments),0);
    }

    private void validateValue(JsonNode value,int depth) {
        if(depth>20) throw new IllegalArgumentException("Слишком глубокое значение параметра");
        if(value.isTextual() && value.asText().startsWith("=")) expressions.validateValueExpression(value.asText().substring(1));
        if(value.isContainerNode()) {
            if(value.size()>1000) throw new IllegalArgumentException("В параметре слишком много элементов");
            value.forEach(child -> validateValue(child,depth+1));
        }
    }

    public boolean evaluateRule(Object specification, JsonNode payload) {
        return evaluateRule(mapper.valueToTree(specification), payload, 0);
    }

    private boolean evaluateRule(JsonNode rule, JsonNode payload, int depth) {
        if (depth > 20 || !rule.isObject()) throw new IllegalArgumentException("Некорректная структура условия");
        if (rule.has("all") || rule.has("any")) {
            boolean all = rule.has("all"); JsonNode children = rule.path(all ? "all" : "any");
            if (!children.isArray() || children.size() > 100) throw new IllegalArgumentException("Некорректная группа условий");
            for (JsonNode child : children) if (evaluateRule(child, payload, depth + 1) != all) return !all;
            return all;
        }
        if (rule.has("not")) return !evaluateRule(rule.path("not"), payload, depth + 1);
        ObjectNode local = payload.deepCopy();
        values.setVariable(local, "_left", resolve(mapper.convertValue(rule.path("left"), Object.class), payload));
        values.setVariable(local, "_right", resolve(mapper.convertValue(rule.path("right"), Object.class), payload));
        String op = rule.path("operator").asText("true");
        String expression = switch (op) {
            case "true" -> "var._left";
            case "null" -> "var._left.isNull()";
            case "notNull" -> "var._left.notNull()";
            case "empty" -> "var._left.isEmpty()";
            case "notEmpty" -> "var._left.notEmpty()";
            case "=", "==", ">", "<", ">=", "<=", "in" -> "var._left " + op + " var._right";
            case "!=" -> "!(var._left = var._right)";
            default -> throw new IllegalArgumentException("Неизвестное сравнение: " + op);
        };
        return expressions.evaluateExpression(expression, local);
    }

    public JsonNode execute(String operation, Map<String, Object> arguments, AutomationExecutionContext ctx,
                            boolean dryRun, String executionKey, Object testResult) {
        Map<String, Object> spec = definition(operation);
        String effect = String.valueOf(spec.get("effect"));
        if (dryRun && testResult != null) return mapper.valueToTree(testResult);
        if (dryRun && "ANALYSIS".equals(effect)) {
            if (testResult == null) throw new IllegalArgumentException("Задайте тестовый ответ для узла анализа: " + operation);
            return mapper.valueToTree(testResult);
        }
        if (dryRun && ("WRITE".equals(effect) || "CONTROL".equals(effect))) return preview(operation, arguments);
        if ("READ".equals(effect)) return transactions.read(() -> mapper.valueToTree(invoke(operation, arguments, ctx)));
        if (!"WRITE".equals(effect) && !"ANALYSIS".equals(effect)) return mapper.valueToTree(invoke(operation, arguments, ctx));
        if (executionKey == null || executionKey.isBlank()) throw new IllegalArgumentException("Отсутствует ключ выполнения операции");
        return transactions.once(executionKey, operation, () -> mapper.valueToTree(invoke(operation, arguments, ctx)));
    }

    public void refresh(Event event) {
        Event copy = Event.builder().payload(event.getPayload().deepCopy()).triggerType(event.getTriggerType()).build();
        JsonNode fresh = transactions.read(() -> { payloadService.enrich(copy); return copy.getPayload(); });
        fresh.fields().forEachRemaining(entry -> {
            if (!entry.getKey().equals("_automation")) ((ObjectNode) event.getPayload()).set(entry.getKey(), entry.getValue());
        });
    }

    public JsonNode loopSnapshot(String key, JsonNode items) {
        return transactions.once(key, "loop.snapshot", () -> items);
    }

    private JsonNode preview(String id, Map<String, Object> a) {
        if (id.equals("state.claim")) return mapper.valueToTree(Map.of("acquired", true));
        if (id.equals("task.create")) return mapper.valueToTree(m("id", -1L, "name", a.get("title"), "serviceId", a.get("serviceId")));
        if (id.equals("incident.create") || id.equals("signal.create")) return mapper.valueToTree(m("id", -1L, "number", "ТЕСТ", "title", a.get("title")));
        return mapper.valueToTree(Map.of("preview", true));
    }

    private Object invoke(String op, Map<String, Object> a, AutomationExecutionContext ctx) {
        return switch (op) {
            case "signal.analyze", "signal.verify", "signal.count", "signal.cooldown", "signal.mark", "signal.attach", "signal.create" -> signalOperation(op, a, ctx);
            case "value.set" -> a.get("value");
            case "lock.key" -> { jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", str(a, "key")); yield m("locked", true); }
            case "data.tasks" -> selectTasks(a);
            case "data.task" -> tasks.findById(required(a, "taskId")).map(this::task).orElse(null);
            case "data.service" -> services.findById(required(a, "serviceId")).map(this::service).orElse(null);
            case "data.messages" -> clients.findById(required(a, "clientId")).map(c -> c.getMessages().stream()
                    .filter(x -> !bool(a, "incomingOnly") || (!Boolean.TRUE.equals(x.getIsSent())
                            && !Boolean.TRUE.equals(x.getIsComment()) && !Boolean.TRUE.equals(x.getDeleted())
                            && !(x.getUser() != null && org.hibernate.Hibernate.unproxy(x.getUser()) instanceof SystemUser)))
                    .sorted(bool(a, "newestFirstById")
                            ? Comparator.comparing(Message::getId, Comparator.nullsLast(Comparator.reverseOrder()))
                            : Comparator.comparing(Message::getDate, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(limit(a))
                    .map(x -> m("id", x.getId(), "text", x.getText(), "date", x.getDate(), "sent", x.getIsSent(),
                            "comment", x.getIsComment(), "deleted", x.getDeleted(), "fromSource", x.getFromSource(),
                            "system", x.getUser() != null && org.hibernate.Hibernate.unproxy(x.getUser()) instanceof SystemUser)).toList()).orElse(List.of());
            case "data.services" -> {
                Set<Long> available = new HashSet<>();
                catalog.getAvailableServices(id(a, "organizationId")).forEach(x -> available.add(((Number) x.get("id")).longValue()));
                yield services.findAllByActiveTrueOrderByOrderNumberAscNameAsc().stream().filter(x -> available.contains(x.getId()))
                        .limit(limit(a)).map(this::service).toList();
            }
            case "data.taskTypes" -> types.findAll().stream().limit(limit(a)).map(x -> m("id", x.getId(), "name", x.getType())).toList();
            case "data.priorities" -> priorities.findAll().stream().sorted().limit(limit(a)).map(x -> m("id", x.getId(), "name", x.getName(), "critical", x.getCritical(), "order", x.getOrderNumber())).toList();
            case "data.lines" -> lines.findAll().stream().limit(limit(a)).map(x -> m("id", x.getId(), "name", x.getName(),
                    "description", x.getDescription(), "level", x.getLevel(), "active", x.getActive(), "responsibleId", x.getResponsible() == null ? null : x.getResponsible().getId(),
                    "memberIds", x.getMembers().stream().map(User::getId).toList())).toList();
            case "data.articles" -> knowledge.getKnowledgeBase().stream().limit(limit(a)).map(x -> m("id", x.getId(), "name", x.getTitle(),
                    "text", x.getTexts() == null ? "" : String.join("\n", x.getTexts()))).toList();
            case "data.users" -> users.findAll().stream().limit(limit(a)).map(x -> m("id", x.getId(), "name", x.getUsername(),
                    "enabled", x.isEnabled(), "admin", x.getAuthorities() != null && x.getAuthorities().contains(Role.ADMIN))).toList();
            case "data.incidents" -> incidents.findAll().stream().filter(x -> id(a, "serviceId") == null || x.getServices().stream().anyMatch(s -> Objects.equals(s.getId(), id(a, "serviceId"))))
                    .sorted(Comparator.comparing(SupportIncident::getId).reversed()).limit(limit(a)).map(this::incident).toList();
            case "data.links" -> selectLinks(a);
            case "data.monitoringCount" -> monitoring.countByServiceIdAndStatus(required(a, "serviceId"), MonitoringProblem.Status.valueOf(str(a, "status")));
            case "data.monitoring" -> monitoring.findAll().stream().filter(x -> x.getService() != null && Objects.equals(x.getService().getId(), required(a, "serviceId")))
                    .limit(limit(a)).map(x -> m("id", x.getId(), "status", x.getStatus(), "severity", x.getSeverity(), "name", x.getName())).toList();
            case "data.affectedClients" -> {
                Set<Long> organizations = new HashSet<>();
                assignments.findAllByServiceIdOrderByOrganizationNameAsc(required(a, "serviceId")).stream().filter(x -> x.isEffectiveOn(null))
                        .filter(x -> x.getOrganization() != null).forEach(x -> organizations.add(x.getOrganization().getId()));
                yield organizations.isEmpty() ? 0L : clients.countByOrganizationIdIn(organizations);
            }
            case "list.select" -> selectList(a, ctx.getEvent().getPayload());
            case "list.project" -> project(a, ctx.getEvent().getPayload());
            case "list.concat" -> Stream.concat(array(a.get("first")).stream(), array(a.get("second")).stream()).distinct().toList();
            case "text.limit" -> {
                String text = str(a, "text"); if (bool(a,"firstLine")) text = text.lines().findFirst().orElse("").strip(); int max = Math.max(1, number(a, "length", 80));
                yield text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
            }
            case "ai.decide" -> decide(a, ctx);
            case "ai.generate" -> {
                String text = llm.askLlm(str(a, "prompt"));
                if (text == null || text.isBlank()) throw new IllegalStateException("ИИ вернул пустой текст");
                yield m("text", text);
            }
            case "task.create" -> createTask(a);
            case "task.update" -> updateTask(a, ctx);
            case "task.addTag", "task.removeTag", "task.assignLeastLoaded" -> {
                String method = switch (op) {
                    case "task.addTag" -> "task.addTag";
                    case "task.removeTag" -> "task.removeTag";
                    default -> "task.assignToLeastLoadedMember";
                };
                Object value = op.equals("task.assignLeastLoaded") ? required(a, "lineId") : str(a, "tag");
                if (value instanceof String text && text.isBlank()) throw new IllegalArgumentException("Укажите тег");
                call(method, List.of(value), a, ctx);
                yield task(tasks.findById(required(a, "taskId")).orElseThrow());
            }
            case "client.note", "client.message" -> {
                call(op.equals("client.note") ? "client.addInternalNote" : "client.sendMessage", List.of(str(a, "text")), a, ctx);
                yield m("sent", true);
            }
            case "client.replyFlag" -> { call("client.setAnswerRequired", List.of(bool(a, "required")), a, ctx); yield m("changed", true); }
            case "incident.create" -> createIncident(a);
            case "incident.note" -> { incidentService.addSystemNote(required(a, "incidentId"), str(a, "text")); yield m("changed", true); }
            case "incident.confirm" -> m("changed", incidentService.setRecoveryConfirmationForSystem(required(a, "incidentId"), required(a, "taskId"), bool(a, "confirmed"), str(a, "reason")));
            case "incident.reportProblem" -> { incidentService.reportRecoveryProblemForSystem(required(a, "incidentId"), required(a, "taskId"), str(a, "text")); yield m("changed", true); }
            case "incident.observe" -> m("status", incidentService.moveToRecoveryCheckForSystem(required(a, "incidentId"), str(a, "reason")).name());
            case "incident.linkTasks" -> incidentService.linkTasksForSystem(required(a, "incidentId"), array(a.get("taskIds")).stream().map(JsonNode::asLong).toList());
            case "notify.user" -> { call("notify.user", List.of(required(a, "userId"), str(a, "text")), a, ctx); yield m("sent", true); }
            case "state.release" -> m("released", jdbc.update("delete from automation_graph_memory where memory_key = ?", str(a, "key")) > 0);
            case "state.claim" -> m("acquired", jdbc.update("insert into automation_graph_memory(memory_key) values (?) on conflict do nothing", str(a, "key")) == 1);
            default -> throw new IllegalArgumentException("Неизвестная операция: " + op);
        };
    }

    private Object selectTasks(Map<String, Object> a) {
        Long clientId = id(a, "clientId");
        List<Task> source = clientId != null ? clients.findById(clientId).map(Client::getTasks).orElse(List.of())
                : tasks.findOpenCreatedSince(ZonedDateTime.now().minusHours(Math.max(1, number(a, "hours", 72))), org.springframework.data.domain.PageRequest.of(0, limit(a)));
        return source.stream().sorted(Comparator.comparing(Task::getId).reversed()).limit(limit(a)).map(this::task).toList();
    }

    private Object selectLinks(Map<String, Object> a) {
        List<IncidentTaskLink> source;
        if (id(a, "taskId") != null) source = links.findFirstByTaskIdAndActiveTrue(id(a, "taskId")).map(List::of).orElse(List.of());
        else if (id(a, "incidentId") != null) source = links.findAllByIncidentIdAndActiveTrueOrderByLinkedAtAsc(id(a, "incidentId"));
        else if (id(a, "clientId") != null) source = clients.findById(id(a, "clientId")).map(c -> c.getTasks().stream()
                .flatMap(t -> links.findFirstByTaskIdAndActiveTrue(t.getId()).stream()).toList()).orElse(List.of());
        else source = links.findAll().stream().filter(x -> Boolean.TRUE.equals(x.getActive())).toList();
        return source.stream().limit(limit(a)).map(x -> m("id", x.getId(), "taskId", x.getTask().getId(), "incident", incident(x.getIncident()),
                "task", task(x.getTask()), "confirmed", x.getRecoveryConfirmed())).toList();
    }

    private Object selectList(Map<String, Object> a, JsonNode payload) {
        List<JsonNode> items = new ArrayList<>(array(a.get("items")));
        String where = str(a, "where");
        items.removeIf(item -> !where.isBlank() && !expressions.evaluateExpression(where, itemPayload(payload, item)));
        String sort = str(a, "sortBy");
        if (!sort.isBlank()) {
            Comparator<JsonNode> comparator = (x, y) -> compare(itemValue(sort, x, payload), itemValue(sort, y, payload));
            if (bool(a, "descending")) comparator = comparator.reversed();
            items.sort(comparator);
        }
        String distinctBy = str(a, "distinctBy");
        Set<JsonNode> seen = new HashSet<>();
        if (!distinctBy.isBlank()) items.removeIf(item -> !seen.add(itemValue(distinctBy, item, payload)));
        return items.stream().limit(limit(a)).toList();
    }

    private Object project(Map<String, Object> a, JsonNode payload) {
        return array(a.get("items")).stream().map(item -> expressions.evaluateValue(str(a, "expression"), itemPayload(payload, item))).toList();
    }

    private JsonNode itemPayload(JsonNode payload, JsonNode item) {
        JsonNode copy = payload.deepCopy(); values.setVariable(copy, "item", item); return copy;
    }

    private Object decide(Map<String, Object> a, AutomationExecutionContext ctx) {
        String kind = str(a, "kind"); if (kind.isBlank()) kind = "choice";
        if (!Set.of("choice", "noul").contains(kind)) throw new IllegalArgumentException("Тип ответа: choice или noul");
        Map<String, Object> criteria = new LinkedHashMap<>(); Map<String, JsonNode> objects = new LinkedHashMap<>();
        for (JsonNode candidate : array(a.get("candidates"))) {
            String key = candidate.path("id").asText();
            if (key.isBlank()) throw new IllegalArgumentException("У варианта отсутствует id");
            String label = str(a, "labelExpression").isBlank()
                    ? itemValue(str(a, "labelField").isBlank() ? "var.item.name" : str(a, "labelField"), candidate, ctx.getEvent().getPayload()).asText(key)
                    : Objects.toString(expressions.evaluateValue(str(a, "labelExpression"), itemPayload(ctx.getEvent().getPayload(), candidate)), key);
            criteria.put(key, label); objects.put(key, candidate);
        }
        JsonNode options = mapper.valueToTree(a.get("options"));
        if (options != null && options.isObject()) options.fields().forEachRemaining(e -> criteria.put(e.getKey(), e.getValue().asText()));
        if (kind.equals("noul")) { criteria.clear(); criteria.put("true", str(a, "yes")); criteria.put("false", str(a, "no")); }
        if (criteria.isEmpty()) {
            decisionRecorder.noOptions("automation=" + (ctx.getTrigger() == null ? "test" : ctx.getTrigger().getId()), str(a, "question"));
            return m("available", false, "status", "NO_OPTIONS");
        }
        Optional<JsonNode> answer = jev.evaluate("automation=" + (ctx.getTrigger() == null ? "test" : ctx.getTrigger().getId()),
                a.get("state"), Map.of("decision", m("type", kind, "instructions", str(a, "question"), "criteria", criteria)));
        if (answer.isEmpty()) return m("available", false, "status", "UNAVAILABLE");
        JsonNode result = answer.get().path("decision");
        if (kind.equals("noul")) {
            if (!result.path("noul").isNumber()) return m("available", false, "status", "INVALID_RESPONSE");
            return m("available", true, "status", "OK", "probability", probability(result.path("noul").asDouble()));
        }
        String selected = result.path("choice").asText();
        if (!criteria.containsKey(selected) || !result.path("confidence").isNumber()) return m("available", false, "status", "INVALID_RESPONSE");
        return m("available", true, "status", "OK", "choice", selected, "probability", probability(result.path("confidence").asDouble()), "value", objects.get(selected));
    }

    private Map<String, Object> createTask(Map<String, Object> a) {
        Task.TaskBuilder builder = Task.builder().name(str(a, "title")).description(str(a, "description"))
                .status(statuses.findByDefaultSelectionTrue().orElseThrow()).priority(id(a, "priorityId") == null
                        ? priorities.findByDefaultSelectionTrue().orElseThrow() : priorities.findById(id(a, "priorityId")).orElseThrow())
                .linkedMessageId(id(a, "messageId"));
        if (id(a, "serviceId") != null) builder.service(services.findById(id(a, "serviceId")).orElseThrow());
        if (id(a, "typeId") != null) builder.type(types.findById(id(a, "typeId")).orElseThrow());
        if (id(a, "lineId") != null) builder.supportLine(lines.findById(id(a, "lineId")).orElseThrow());
        return task(tasksSave(builder, required(a, "clientId")));
    }

    private final TaskService taskService;
    private Task tasksSave(Task.TaskBuilder builder, Long clientId) {
        if (builder.build().getName().isBlank()) throw new IllegalArgumentException("Название заявки пустое");
        return taskService.newTaskForSystem(clientId, builder.build());
    }

    private Object updateTask(Map<String, Object> a, AutomationExecutionContext ctx) {
        Map<String, Object> fields = mapper.convertValue(a.get("fields"), new TypeReference<>() {});
        if (fields == null) throw new IllegalArgumentException("Укажите изменения заявки");
        for (String key : fields.keySet()) if (!Set.of("name", "description", "serviceId", "typeId", "priorityId", "lineId", "status", "assigneeId", "clearAssignee").contains(key))
            throw new IllegalArgumentException("Неизвестное поле заявки: " + key);
        // Service first: type compatibility is checked against the final service.
        for (String key : List.of("serviceId", "typeId", "name", "description", "priorityId", "lineId", "status", "assigneeId", "clearAssignee")) {
            Object value = fields.get(key); if (value == null) continue;
            String method = switch (key) { case "serviceId" -> "setService"; case "typeId" -> "setType"; case "name" -> "setName";
                case "description" -> "setDescription"; case "priorityId" -> "setPriority"; case "lineId" -> "assignToGroup";
                case "status" -> "setStatus"; case "assigneeId" -> "assignToUser"; default -> "clearAssignee"; };
            if (key.equals("clearAssignee") && !Boolean.TRUE.equals(value)) continue;
            if (key.equals("priorityId")) value = priorities.findById(Long.valueOf(value.toString())).orElseThrow().getName();
            call("task." + method, key.equals("clearAssignee") ? List.of() : List.of(value), a, ctx);
        }
        return task(tasks.findById(required(a, "taskId")).orElseThrow());
    }

    private Object createIncident(Map<String, Object> a) {
        SupportIncident result = incidentService.createDraftForSystem(new IncidentService.SystemIncidentDraft(str(a, "title"), str(a, "description"),
                str(a, "publicDescription"), str(a, "impact"), IncidentSeverity.valueOf(str(a, "severity")), required(a, "serviceId"), id(a, "lineId"),
                id(a, "ownerId"), id(a, "ownerId"), array(a.get("participantIds")).stream().map(JsonNode::asLong).toList(), List.of(), List.of(),
                a.get("startedAt") == null ? ZonedDateTime.now() : ZonedDateTime.parse(a.get("startedAt").toString()), number(a, "intervalMinutes", 60)), "автоматизация");
        return incident(result);
    }

    private Object call(String name, List<?> args, Map<String, Object> a, AutomationExecutionContext parent) {
        ObjectNode payload = parent.getEvent().getPayload().deepCopy();
        for (String entity : List.of("client", "task", "message")) {
            Long entityId = id(a, entity + "Id");
            if (entityId != null) payload.with(entity).put("id", entityId);
        }
        Event event = Event.builder().payload(payload).id(parent.getEvent().getId()).actorUserId(parent.getEvent().getActorUserId()).actorUsername(parent.getEvent().getActorUsername()).actorDisplayName(parent.getEvent().getActorDisplayName()).actorType(parent.getEvent().getActorType())
                .triggerType(parent.getEvent().getTriggerType()).build();
        ObjectNode action = mapper.createObjectNode(); action.set("args", mapper.valueToTree(args));
        return actions.execute(name, action, new AutomationExecutionContext(parent.getTrigger(), event));
    }

    private Object signalOperation(String operation, Map<String, Object> arguments, AutomationExecutionContext context) {
        String method = switch (operation) {
            case "signal.analyze" -> "jev.analyzeOutage"; case "signal.verify" -> "jev.verifyBatch";
            case "signal.count" -> "incident.countSignals"; case "signal.cooldown" -> "incident.checkCooldown";
            case "signal.mark" -> "incident.markSignal"; case "signal.attach" -> "incident.attachSignal";
            default -> "incident.createDraftFromSignals";
        };
        @SuppressWarnings("unchecked") List<Map<String, Object>> fields = (List<Map<String, Object>>) definition(operation).get("fields");
        List<Object> args = fields.stream().map(f -> arguments.get(f.get("key").toString())).toList();
        Object result = call(method, args, arguments, context);
        if (result == null && Set.of("signal.analyze", "signal.verify").contains(operation))
            decisionRecorder.recordUnsentGraphAnalysis("automation=" + (context.getTrigger() == null ? "test" : context.getTrigger().getId()), operation);
        return result;
    }

    private Map<String, Object> task(Task x) {
        return m("id", x.getId(), "name", x.getName(), "description", x.getDescription(), "completed", x.getCompleted(),
                "serviceId", x.getService() == null ? null : x.getService().getId(), "typeId", x.getType() == null ? null : x.getType().getId(),
                "clientId", clients.findByTaskId(x.getId()).map(Client::getId).orElse(null),
                "incident", links.findFirstByTaskIdAndActiveTrue(x.getId()).map(link -> incident(link.getIncident())).orElse(null),
                "lineId", x.getSupportLine() == null ? null : x.getSupportLine().getId(), "lineLevel", x.getSupportLine() == null ? null : x.getSupportLine().getLevel(),
                "assigneeId", x.getExecutor() == null ? null : x.getExecutor().getId(),
                "enteredLineAt", x.getEnteredCurrentLineAt(), "createdAt", x.getCreatedAt(), "closedAt", x.getClosedAt(), "lastActivity", x.getLastActivity());
    }
    private Map<String, Object> service(ru.ravel.ItDesk.model.Service x) {
        return m("id", x.getId(), "name", x.getName(), "description", x.getDescription(), "allowedTypeIds", x.getAllowedTaskTypes().stream().map(TaskType::getId).toList(),
                "ownerId", x.getOwner() == null ? null : x.getOwner().getId(), "executorIds", x.getExecutors().stream().map(User::getId).toList(),
                "lineId", x.getDefaultSupportLine() == null ? null : x.getDefaultSupportLine().getId());
    }
    private Map<String, Object> incident(SupportIncident x) {
        return m("id", x.getId(), "number", x.getIncidentNumber(), "name", x.getTitle(), "title", x.getTitle(), "description", x.getPublicDescription(),
                "status", x.getStatus().name(), "severity", x.getSeverity().name(), "serviceIds", x.getServices().stream().map(ru.ravel.ItDesk.model.Service::getId).toList());
    }
    private List<JsonNode> array(Object x) { JsonNode node = mapper.valueToTree(x); if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException("Ожидается список"); List<JsonNode> result = new ArrayList<>(); node.forEach(result::add); return result; }
    private JsonNode path(JsonNode node, String path) { for (String field : path.split("\\.")) node = node.path(field); return node; }
    private int compare(JsonNode a, JsonNode b) { if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()); return a.asText("").compareTo(b.asText("")); }
    private Long id(Map<String, Object> a, String k) { Object v = a.get(k); if (v == null || v.toString().isBlank()) return null; return new java.math.BigDecimal(v.toString()).longValueExact(); }
    private Long required(Map<String, Object> a, String k) { Long result = id(a, k); if (result == null) throw new IllegalArgumentException("Укажите " + k); return result; }
    private String str(Map<String, Object> a, String k) { return Objects.toString(a.get(k), ""); }
    private int number(Map<String, Object> a, String k, int fallback) { return a.get(k) == null ? fallback : new java.math.BigDecimal(a.get(k).toString()).intValueExact(); }
    private int limit(Map<String, Object> a) { return Math.min(1000, Math.max(1, number(a, "limit", 100))); }
    private boolean bool(Map<String, Object> a, String k) { return Boolean.TRUE.equals(a.get(k)); }
    private double probability(double p) { if (!Double.isFinite(p) || p < 0 || p > 1) throw new IllegalArgumentException("Некорректная вероятность Jev"); return p; }
    private static Map<String, Object> m(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i].toString(), pairs[i+1] instanceof java.time.temporal.TemporalAccessor ? pairs[i+1].toString() : pairs[i+1]); return result; }
}
