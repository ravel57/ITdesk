package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.PriorityRepository;
import ru.ravel.ItDesk.repository.StatusRepository;
import ru.ravel.ItDesk.repository.TagRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;


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
			clientService.sendMessageWithUser(clientId, message, SystemUser.getInstance());
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
			Task task = clientService.newTask(clientId, Task.builder()
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

		public void setStatus(String status) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || status == null || status.isBlank()) {
				log.warn("task.setStatus skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			task.setStatus(statusRepository.findByName(status).orElseThrow());
			taskRepository.save(task);
			log.info("AUTO task.setStatus(taskId={}, status={})", taskId, status);
		}

		public void setPriority(String priority) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || priority == null) {
				log.warn("task.setPriority skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			task.setPriority(priorityRepository.findByName(priority).orElseThrow());
			taskRepository.save(task);
			log.info("AUTO task.setPriority(taskId={}, priority={})", taskId, priority);
		}

		public void addTag(String tag) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || tag == null || tag.isBlank()) {
				log.warn("task.addTag skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			task.getTags().add(tagRepository.findByName(tag).orElseThrow());
			taskRepository.save(task);
			log.info("AUTO task.addTag(taskId={}, tag={})", taskId, tag);
		}

		public void removeTag(String tag) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || tag == null || tag.isBlank()) {
				log.warn("task.removeTag skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			Task task = taskRepository.findById(taskId).orElseThrow();
			task.getTags().remove(tagRepository.findByName(tag).orElseThrow());
			taskRepository.save(task);
			log.info("AUTO task.removeTag(taskId={}, tag={})", taskId, tag);
		}

		public void assignToUser(Long userId) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || userId == null) {
				log.warn("task.assignToUser skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			// TODO: taskService.assignToUser(taskId, userId)
			log.info("AUTO task.assignToUser(taskId={}, userId={})", taskId, userId);
		}

		public void assignToGroup(Long groupId) {
			Long taskId = resolveTaskId(ctx);
			if (taskId == null || groupId == null) {
				log.warn("task.assignToGroup skipped: invalid args, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			// TODO: taskService.assignToGroup(taskId, groupId)
			log.info("AUTO task.assignToGroup(taskId={}, groupId={})", taskId, groupId);
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
		if (n.isTextual()) return n.asText();
		if (n.isBoolean()) return n.asBoolean();
		if (n.isNumber()) return n.decimalValue();
		if (n.isArray()) {
			List<Object> list = new ArrayList<>();
			for (JsonNode x : n) list.add(unwrap(x));
			return list;
		}
		// object — оставим JsonNode, чтобы не терять структуру
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


	private Long asLong(Object v) {
		switch (v) {
			case Long l -> {
				return l;
			}
			case Integer i -> {
				return i.longValue();
			}
			case BigDecimal bd -> {
				return bd.longValue();
			}
			case String s -> {
				try {
					return Long.parseLong(s.trim());
				} catch (Exception ignored) {
					return null;
				}
			}
			case null, default -> {
				return null;
			}
		}
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
