package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.POJONode;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.*;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;


/**
 * Исполнитель действий из поля "Действие" (как на скриншоте):
 * client.sendMessage('Здравствуйте!')
 * ticket.setStatus('IN_PROGRESS')
 * ticket.addTag('vip')
 * Скриптовый рантайм передаёт сюда:
 * actionType = "client.sendMessage"
 * actionNode = { "args": ["Здравствуйте!"] }
 * ВАЖНО:
 * - Тут нет ролей/прав (вообще никак)
 * - Конкретные "реальные действия" пока сделаны как лог (помечены TODO),
 * ты просто подключишь нужные сервисы и заменишь TODO на бизнес-логику.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationActionExecutor {

	private final ClientService clientService;
	private final PriorityRepository priorityRepository;
	private final StatusRepository statusRepository;
	private final TaskFilterService taskFilterService;
	private final TaskRepository taskRepository;
	private final TagRepository tagRepository;
	private final TaskService taskService;
	private final UserRepository userRepository;
	private final TaskRoutingService taskRoutingService;
	private final WebSocketService webSocketService;
	private final EventPublisher eventPublisher;

	/**
	 * Приходит из AutomationScriptRuntime:
	 * <p>
	 * actionType = "client.sendMessage"
	 * actionNode = { "args": ["Принято!"] }
	 */
	public void execute(String actionType, JsonNode actionNode, AutomationExecutionContext ctx) {
		if (actionType == null || actionType.isBlank() || ctx == null) {
			return;
		}
		List<Object> args = parseArgs(actionNode);
		ActionCall call = ActionCall.parse(actionType);
		if (call == null) {
			log.warn("Invalid actionType='{}'", actionType);
			return;
		}
		AutomationApi api = new AutomationApi(ctx);
		Object target = api.resolveTarget(call.target);
		if (target == null) {
			log.warn("Unknown target '{}', actionType='{}'", call.target, actionType);
			return;
		}
		try {
			invoke(target, call.method, args);
		} catch (Exception e) {
			log.error("Action failed: {}({}) event={}",
					actionType, args, safeEventInfo(ctx.getEvent()), e);
			throw new RuntimeException(e);
		}
	}

	// ======================================================================
	// API OBJECTS (под них удобно делать саджесты на фронте)
	// ======================================================================

	/**
	 * Контекст, который в скриптах выглядит как "client", "task", "notify" и т.д.
	 * ВАЖНО: методы тут должны быть "человеческие", чтобы их удобно предлагать в UI.
	 */
	public final class AutomationApi {

		private final AutomationExecutionContext ctx;

		public AutomationApi(AutomationExecutionContext ctx) {
			this.ctx = ctx;
		}

		public ClientApi client() {
			return new ClientApi(ctx);
		}

		public TaskApi task() {
			return new TaskApi(ctx);
		}

		public NotifyApi notifyApi() {
			return new NotifyApi(ctx);
		}

		public WebhookApi webhook() {
			return new WebhookApi(ctx);
		}

		@Nullable
		Object resolveTarget(String name) {
			return switch (name) {
				case "client" -> client();
				case "task" -> task();
				case "notify" -> notifyApi();
				case "webhook" -> webhook();
				default -> null;
			};
		}
	}

	/**
	 * "client.*" — реальный набор операций над клиентом.
	 * На фронте можно саджестить методы и параметры отсюда.
	 */
	public final class ClientApi {
		private final AutomationExecutionContext ctx;

		ClientApi(AutomationExecutionContext ctx) {
			this.ctx = ctx;
		}

		public Long id() {
			return resolveClientId(ctx);
		}

		public void sendMessage(String text) {
			text = normalizeOutgoingMessageText(text);
			Long clientId = resolveClientId(ctx);
			if (clientId == null) {
				log.warn("client.sendMessage skipped: clientId is null, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			if (text == null || text.isBlank()) {
				log.warn("client.sendMessage skipped: text is blank, clientId={}", clientId);
				return;
			}
			Message message = Message.builder()
					.text(text)
					.isSent(true)
					.isRead(true)
					.date(ZonedDateTime.now())
					.isComment(false)
					.build();
			clientService.sendMessageForSystem(clientId, message);
			log.info("AUTO client.sendMessage(clientId={}, text={})", clientId, text);
		}

		public void setField(String field, Object value) {
			Long clientId = resolveClientId(ctx);
			if (clientId == null || field == null || field.isBlank()) {
				log.warn("client.setField skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}

			// TODO: подставишь реальную логику обновления клиента
			// clientService.updateField(clientId, field, value);

			log.info("AUTO client.setField(clientId={}, field={}, value={})", clientId, field, value);
		}
	}

	/**
	 * "task.*" — операции над заявкой (таской).
	 * Сейчас заглушки, но методы уже есть (для саджестов).
	 */
	public final class TaskApi {
		private final AutomationExecutionContext ctx;

		TaskApi(AutomationExecutionContext ctx) {
			this.ctx = ctx;
		}

		public Long create(String title) {
			Long clientId = resolveClientId(ctx);
			if (clientId == null || title == null || title.isBlank()) return null;
			Task task = taskService.newTaskForSystem(clientId, Task.builder()
					.priority(priorityRepository.findByDefaultSelectionTrue().orElseThrow())
					.status(statusRepository.findByDefaultSelectionTrue().orElseThrow())
					.name(title)
					.build());
			log.info("AUTO task.create(clientId={}, title={})", clientId, title);
			return task.getId();
		}

		public Long id() {
			return resolveTaskId(ctx);
		}

		public void setName(String name) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || name == null || name.isBlank()) {
				log.warn("task.setName skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			String oldName = task.getName();
			String newName = name.trim();
			if (Objects.equals(oldName, newName)) {
				return;
			}
			task.setName(newName);
			task.setLastActivity(ZonedDateTime.now());
			taskRepository.save(task);
			publishTaskEvent(TriggerType.TASK_UPDATED, task, ctx,
					"changes", List.of(taskChange("name", "Название", oldName, newName)));
			notifyTaskUpdated(taskId);
		}


		public void setDescription(String description) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || description == null) {
				log.warn("task.setDescription skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			String oldDescription = task.getDescription();
			if (Objects.equals(oldDescription, description)) {
				return;
			}
			task.setDescription(description);
			task.setLastActivity(ZonedDateTime.now());
			taskRepository.save(task);
			publishTaskEvent(TriggerType.TASK_UPDATED, task, ctx,
					"changes", List.of(taskChange("description", "Описание", oldDescription, description)));
			notifyTaskUpdated(taskId);
		}


		public void setDeadlineAfterMinutes(Long minutes) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || minutes == null || minutes < 1) {
				log.warn("task.setDeadlineAfterMinutes skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			ZonedDateTime oldDeadline = task.getDeadline();
			ZonedDateTime newDeadline = ZonedDateTime.now().plusMinutes(minutes);
			task.setDeadline(newDeadline);
			task.setLastActivity(ZonedDateTime.now());
			taskRepository.save(task);
			publishTaskEvent(TriggerType.TASK_DUE_DATE_CHANGED, task, ctx,
					"oldDeadline", oldDeadline,
					"newDeadline", newDeadline);
			notifyTaskUpdated(taskId);
		}


		public void clearDeadline() {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null) {
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			ZonedDateTime oldDeadline = task.getDeadline();
			if (oldDeadline == null) {
				return;
			}
			task.setDeadline(null);
			task.setLastActivity(ZonedDateTime.now());
			taskRepository.save(task);
			publishTaskEvent(TriggerType.TASK_DUE_DATE_CHANGED, task, ctx,
					"oldDeadline", oldDeadline,
					"newDeadline", null);
			notifyTaskUpdated(taskId);
		}


		public void setStatus(String status) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || status == null || status.isBlank()) {
				log.warn("task.setStatus skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			Status oldStatus = task.getStatus();
			Status newStatus = statusRepository.findByName(status).orElseThrow();
			if (sameEntity(oldStatus, newStatus)) {
				return;
			}
			task.setStatus(newStatus);
			task.setLastActivity(ZonedDateTime.now());
			taskRepository.save(task);
			publishTaskEvent(TriggerType.TASK_STATUS_CHANGED, task, ctx,
					"oldStatus", oldStatus,
					"newStatus", newStatus);
			notifyTaskUpdated(taskId);
			log.info("AUTO task.setStatus(taskId={}, status={})", taskId, status);
		}

		public void setPriority(String priority) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || priority == null) {
				log.warn("task.setPriority skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			Priority oldPriority = task.getPriority();
			Priority newPriority = priorityRepository.findByName(priority).orElseThrow();
			if (sameEntity(oldPriority, newPriority)) {
				return;
			}
			task.setPriority(newPriority);
			task.setLastActivity(ZonedDateTime.now());
			taskRepository.save(task);
			publishTaskEvent(TriggerType.TASK_PRIORITY_CHANGED, task, ctx,
					"oldPriority", oldPriority,
					"newPriority", newPriority);
			notifyTaskUpdated(taskId);
			log.info("AUTO task.setPriority(taskId={}, priority={})", taskId, priority);
		}

		public void addTag(String tag) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || tag == null || tag.isBlank()) {
				log.warn("task.addTag skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			Tag foundTag = tagRepository.findByName(tag).orElseThrow();
			if (task.getTags().stream().anyMatch(existing -> sameEntity(existing, foundTag))) {
				return;
			}
			task.getTags().add(foundTag);
			task.setLastActivity(ZonedDateTime.now());
			taskRepository.save(task);
			publishTaskEvent(TriggerType.TASK_TAG_ADDED, task, ctx, "tag", foundTag);
			notifyTaskUpdated(taskId);
			log.info("AUTO task.addTag(taskId={}, tag={})", taskId, tag);
		}

		public void removeTag(String tag) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || tag == null || tag.isBlank()) {
				log.warn("task.removeTag skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			Tag foundTag = tagRepository.findByName(tag).orElseThrow();
			boolean removed = task.getTags().removeIf(existing -> sameEntity(existing, foundTag));
			if (!removed) {
				return;
			}
			task.setLastActivity(ZonedDateTime.now());
			taskRepository.save(task);
			publishTaskEvent(TriggerType.TASK_TAG_REMOVED, task, ctx, "tag", foundTag);
			notifyTaskUpdated(taskId);
			log.info("AUTO task.removeTag(taskId={}, tag={})", taskId, tag);
		}

		public void assignToUser(Long userId) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || userId == null) {
				log.warn("task.assignToUser skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			User oldExecutor = task.getExecutor();
			User newExecutor = userRepository.findById(userId).orElseThrow();
			taskRoutingService.validateExecutor(task.getSupportLine(), newExecutor);
			if (sameEntity(oldExecutor, newExecutor)) {
				return;
			}
			task.setExecutor(newExecutor);
			task.setLastActivity(ZonedDateTime.now());
			taskRepository.save(task);
			publishTaskEvent(TriggerType.TASK_ASSIGNEE_CHANGED, task, ctx,
					"oldExecutor", oldExecutor,
					"newExecutor", newExecutor);
			notifyTaskUpdated(taskId);
			log.info("AUTO task.assignToUser(taskId={}, userId={})", taskId, userId);
		}

		public void clearAssignee() {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null) {
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			User oldExecutor = task.getExecutor();
			if (oldExecutor == null) {
				return;
			}
			task.setExecutor(null);
			task.setLastActivity(ZonedDateTime.now());
			taskRepository.save(task);
			publishTaskEvent(TriggerType.TASK_ASSIGNEE_CHANGED, task, ctx,
					"oldExecutor", oldExecutor,
					"newExecutor", null);
			notifyTaskUpdated(taskId);
			log.info("AUTO task.clearAssignee(taskId={})", taskId);
		}

		public void assignToGroup(Long groupId) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || groupId == null) {
				log.warn("task.assignToGroup skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			taskRoutingService.routeExistingTask(taskId, groupId, false, "Автоматизация");
			notifyTaskUpdated(taskId);
			log.info("AUTO task.assignToGroup(taskId={}, groupId={})", taskId, groupId);
		}


		public void assignToLeastLoadedMember(Long groupId) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || groupId == null) {
				log.warn("task.assignToLeastLoadedMember skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task saved = taskRoutingService.routeExistingTask(taskId, groupId, true, "Автоматизация");
			notifyTaskUpdated(taskId);
			log.info(
					"AUTO task.assignToLeastLoadedMember(taskId={}, groupId={}, userId={})",
					taskId, groupId, saved.getExecutor() == null ? null : saved.getExecutor().getId()
			);
		}

	}


	private void publishTaskEvent(
			TriggerType triggerType,
			Task task,
			AutomationExecutionContext ctx,
			Object... values
	) {
		if (triggerType == null || task == null || task.getId() == null) {
			return;
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("task", task);
		payload.put("source", "AUTOMATION");
		if (ctx != null && ctx.getTrigger() != null) {
			Map<String, Object> automation = new LinkedHashMap<>();
			automation.put("id", ctx.getTrigger().getId());
			automation.put("name", ctx.getTrigger().getName());
			payload.put("automation", automation);
		}
		if (values != null) {
			for (int i = 0; i + 1 < values.length; i += 2) {
				payload.put(String.valueOf(values[i]), values[i + 1]);
			}
		}
		eventPublisher.publish(triggerType, payload);
	}

	private Map<String, Object> taskChange(String field, String label, Object oldValue, Object newValue) {
		Map<String, Object> change = new LinkedHashMap<>();
		change.put("field", field);
		change.put("label", label);
		change.put("oldValue", Objects.toString(oldValue, ""));
		change.put("newValue", Objects.toString(newValue, ""));
		return change;
	}

	private boolean sameEntity(Object left, Object right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		try {
			Method leftId = left.getClass().getMethod("getId");
			Method rightId = right.getClass().getMethod("getId");
			return Objects.equals(leftId.invoke(left), rightId.invoke(right));
		} catch (Exception ignored) {
			return Objects.equals(left, right);
		}
	}

	private void notifyTaskUpdated(Long taskId) {
		if (taskId == null) {
			return;
		}
		try {
			webSocketService.taskUpdated(taskService.buildTaskUpdatedDto(null, taskId));
		} catch (Exception e) {
			log.warn("Unable to send task update after automation, taskId={}", taskId, e);
		}
	}

	public final class NotifyApi {
		private final AutomationExecutionContext ctx;

		NotifyApi(AutomationExecutionContext ctx) {
			this.ctx = ctx;
		}

		public void user(Long userId, String text) {
			if (userId == null || text == null || text.isBlank()) {
				log.warn("notify.user skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}

			// TODO: notificationService.notifyUser(...)
			log.info("AUTO notify.user(userId={}, text={})", userId, text);
		}
	}

	public final class WebhookApi {
		private final AutomationExecutionContext ctx;

		WebhookApi(AutomationExecutionContext ctx) {
			this.ctx = ctx;
		}

		public void call(String url, Object body) {
			if (url == null || url.isBlank()) {
				log.warn("webhook.call skipped: url is blank, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}

			// TODO: webhookService.call(url, body)
			log.info("AUTO webhook.call(url={}, body={})", url, body);
		}
	}

	// ======================================================================
	// INVOKE (рефлексия) — чтобы actionType работал как вызов метода объекта
	// ======================================================================

	private void invoke(Object target, String methodName, List<Object> rawArgs) throws Exception {
		// Критичные системные действия вызываем явно. Это исключает зависимость
		// от порядка/видимости методов reflection и одинаково принимает числа,
		// пришедшие от Jackson как Integer, Long, BigDecimal и другие Number.
		if (invokeKnownAction(target, methodName, rawArgs)) {
			return;
		}

		Method[] methods = target.getClass().getMethods();
		for (Method m : methods) {
			if (!m.getName().equals(methodName)) continue;
			if (m.getDeclaringClass() == Object.class) continue;
			Class<?>[] pt = m.getParameterTypes();
			if (pt.length != rawArgs.size()) continue;
			Object[] converted = new Object[pt.length];
			boolean ok = true;
			for (int i = 0; i < pt.length; i++) {
				Object c = convertArg(rawArgs.get(i), pt[i]);
				if (c == ConversionFailed.INSTANCE) {
					ok = false;
					break;
				}
				converted[i] = c;
			}
			if (!ok) {
				continue;
			}
			m.invoke(target, converted);
			return;
		}
		throw new NoSuchMethodException("No method '%s' for %s args=%s".formatted(methodName, target.getClass().getSimpleName(), rawArgs));
	}

	private boolean invokeKnownAction(Object target, String methodName, List<Object> rawArgs) {
		if (!(target instanceof TaskApi taskApi)) {
			return false;
		}

		switch (methodName) {
			case "assignToGroup" -> {
				taskApi.assignToGroup(requireLongArg(methodName, rawArgs, 0));
				return true;
			}
			case "assignToLeastLoadedMember" -> {
				taskApi.assignToLeastLoadedMember(requireLongArg(methodName, rawArgs, 0));
				return true;
			}
			case "assignToUser" -> {
				taskApi.assignToUser(requireLongArg(methodName, rawArgs, 0));
				return true;
			}
			case "setDeadlineAfterMinutes" -> {
				taskApi.setDeadlineAfterMinutes(requireLongArg(methodName, rawArgs, 0));
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	private Long requireLongArg(String methodName, List<Object> rawArgs, int index) {
		if (rawArgs == null || index < 0 || index >= rawArgs.size()) {
			throw new IllegalArgumentException("Method '%s' requires numeric argument #%d"
					.formatted(methodName, index + 1));
		}
		Long value = asLong(rawArgs.get(index));
		if (value == null) {
			Object raw = rawArgs.get(index);
			throw new IllegalArgumentException("Method '%s' requires numeric argument #%d, got %s (%s)"
					.formatted(
							methodName,
							index + 1,
							raw,
							raw == null ? "null" : raw.getClass().getName()
					));
		}
		return value;
	}

	private Object convertArg(Object raw, Class<?> targetType) {
		if (raw == null) {
			if (targetType.isPrimitive()) return ConversionFailed.INSTANCE;
			return null;
		}

		// JsonNode -> unwrap
		if (raw instanceof JsonNode j) raw = unwrap(j);

		// direct
		if (targetType.isInstance(raw)) return raw;

		// String
		if (targetType == String.class) return asString(raw);

		// Long
		if (targetType == Long.class || targetType == long.class) {
			Long v = asLong(raw);
			return v == null ? ConversionFailed.INSTANCE : v;
		}

		// Integer
		if (targetType == Integer.class || targetType == int.class) {
			Long v = asLong(raw);
			return v == null ? ConversionFailed.INSTANCE : v.intValue();
		}

		// Boolean
		if (targetType == Boolean.class || targetType == boolean.class) {
			if (raw instanceof Boolean b) return b;
			if (raw instanceof String s) return Boolean.parseBoolean(s.trim());
			return ConversionFailed.INSTANCE;
		}

		// Object — можно пропустить всё как есть
		if (targetType == Object.class) return raw;

		// BigDecimal
		if (targetType == BigDecimal.class) {
			if (raw instanceof BigDecimal bd) return bd;
			if (raw instanceof Number n) return new BigDecimal(n.toString());
			if (raw instanceof String s) {
				try {
					return new BigDecimal(s.trim());
				} catch (Exception ignored) {
				}
			}
			return ConversionFailed.INSTANCE;
		}

		return ConversionFailed.INSTANCE;
	}

	private enum ConversionFailed {INSTANCE}

	// ======================================================================
	// PARSE actionType -> target.method
	// ======================================================================

	private record ActionCall(String target, String method) {
		static ActionCall parse(String actionType) {
			int idx = actionType.indexOf('.');
			if (idx <= 0 || idx >= actionType.length() - 1) return null;
			return new ActionCall(actionType.substring(0, idx), actionType.substring(idx + 1));
		}
	}

	// ======================================================================
	// ARGUMENTS (args из actionNode)
	// ======================================================================

	private List<Object> parseArgs(JsonNode actionNode) {
		if (actionNode == null || actionNode.isNull()) {
			return List.of();
		}
		JsonNode argsNode = actionNode.get("args");
		if (argsNode == null || argsNode.isNull() || !argsNode.isArray()) {
			return List.of();
		}
		List<Object> args = new ArrayList<>();
		for (JsonNode a : argsNode) {
			args.add(unwrap(a));
		}
		return args;
	}

	private Object unwrap(JsonNode n) {
		if (n == null || n.isNull()) return null;
		if (n instanceof POJONode pojoNode) {
			Object pojo = pojoNode.getPojo();
			return pojo instanceof JsonNode nestedNode ? unwrap(nestedNode) : pojo;
		}
		if (n.isTextual()) return n.asText();
		if (n.isBoolean()) return n.asBoolean();
		if (n.isNumber()) return n.numberValue();
		if (n.isArray()) {
			List<Object> list = new ArrayList<>();
			for (JsonNode x : n) list.add(unwrap(x));
			return list;
		}
		// Обычный JSON-объект оставляем JsonNode, чтобы не терять структуру.
		return n;
	}

	private Object arg(List<Object> args, int idx) {
		if (args == null || idx < 0 || idx >= args.size()) {
			return null;
		} else {
			return args.get(idx);
		}
	}

	private String asString(Object v) {
		switch (v) {
			case null -> {
				return null;
			}
			case JsonNode j -> {
				if (j.isTextual()) return stripOuterQuotes(j.asText());
				return stripOuterQuotes(j.toString());
			}
			case String s -> {
				return stripOuterQuotes(s);
			}
			default -> {
				return stripOuterQuotes(String.valueOf(v));
			}
		}
	}

	private static String normalizeOutgoingMessageText(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}

		String normalized = text;
		String previous;
		do {
			previous = normalized;
			normalized = normalized
					// Старые сохранённые сценарии могли содержать несколько
					// уровней экранирования: \\n -> \n -> перевод строки.
					.replace("\\\\n", "\\n")
					.replace("\\\\r", "\\r")
					.replace("\\\\t", "\\t")
					.replace("\\\"", "\"")
					.replace("\\'", "'");
		} while (!normalized.equals(previous));

		return normalized
				.replace("\\r\\n", "\n")
				.replace("\\n", "\n")
				.replace("\\r", "\r")
				.replace("\\t", "\t");
	}


	private String stripOuterQuotes(String s) {
		if (s == null) return null;
		s = s.trim();
		if (s.length() >= 2) {
			if ((s.startsWith("\"") && s.endsWith("\"")) ||
					(s.startsWith("'") && s.endsWith("'"))) {
				return s.substring(1, s.length() - 1);
			}
		}
		return s;
	}


	private Long asLong(Object value) {
		// Значение может пройти через несколько Jackson-обёрток:
		// POJONode(JsonNode/Number), ArrayNode из одного элемента и т. п.
		for (int depth = 0; depth < 8; depth++) {
			if (value instanceof POJONode pojoNode) {
				value = pojoNode.getPojo();
				continue;
			}
			if (value instanceof JsonNode node) {
				value = unwrap(node);
				continue;
			}
			if (value instanceof List<?> list && list.size() == 1) {
				value = list.getFirst();
				continue;
			}
			break;
		}

		if (value instanceof Number number) {
			try {
				return new BigDecimal(number.toString()).longValueExact();
			} catch (ArithmeticException | NumberFormatException ignored) {
				return null;
			}
		}
		if (value instanceof String text) {
			try {
				return new BigDecimal(text.trim()).longValueExact();
			} catch (ArithmeticException | NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	// ======================================================================
	// CONTEXT RESOLVERS (id клиента/таски)
	// ======================================================================

	/**
	 * 1) если событие entityType=CLIENT -> entityId
	 * 2) иначе пытаемся достать payload.client.id
	 */
	private Long resolveClientId(AutomationExecutionContext ctx) {
		Event e = ctx.getEvent();
		if (e == null) {
			return null;
		}
		return readLong(e.getPayload(), "client.id");
	}

	private Long resolveTaskId(AutomationExecutionContext ctx) {
		Event e = ctx.getEvent();
		if (e == null) {
			return null;
		}
		return readLong(e.getPayload(), "task.id");
	}

	private Long readLong(JsonNode root, String path) {
		JsonNode n = readByPath(root, path);
		if (n == null || n.isNull()) return null;
		if (n.isNumber()) return n.longValue();
		if (n.isTextual()) {
			try {
				return Long.parseLong(n.asText().trim());
			} catch (Exception ignored) {
				return null;
			}
		}
		return null;
	}

	private JsonNode readByPath(JsonNode root, String path) {
		if (root == null || root.isNull() || path == null || path.isBlank()) {
			return null;
		}
		JsonNode cur = root;
		for (String p : path.split("\\.")) {
			if (cur == null || cur.isNull()) return null;
			cur = cur.get(p);
		}
		return cur;
	}

	private Map<String, Object> safeEventInfo(Event e) {
		if (e == null) return Map.of();
		return Map.of(
				"id", e.getId(),
				"triggerType", e.getTriggerType()//,
//				"entityType", e.getTriggerEntityType(),
		);
	}

}
