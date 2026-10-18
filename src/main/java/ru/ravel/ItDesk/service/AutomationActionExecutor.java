package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.POJONode;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.AnswerRequired;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.model.jev.JevThreshold;
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
	private final WebhookService webhookService;
	private final JevService jevService;
	private final IncidentDetectorService incidentDetectorService;
	private final JevAssistService jevAssistService;
	private final ru.ravel.ItDesk.component.AfterCommitExecutor afterCommitExecutor;
	private final MessageRepository messageRepository;
	private final ClientRepository clientRepository;

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
			if (target instanceof JevApi) {
				// журнал решений Jev знает, какое правило и действие спросили Jev
				invokeJevAction(target, call.method, args, ctx);
			} else {
				invoke(target, call.method, args);
			}
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

		public JevApi jev() {
			return new JevApi(ctx);
		}

		@Nullable
		Object resolveTarget(String name) {
			return switch (name) {
				case "client" -> client();
				case "task" -> task();
				case "notify" -> notifyApi();
				case "webhook" -> webhook();
				case "jev" -> jev();
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

		public void setAnswerRequired() {
			Long clientId = resolveClientId(ctx);
			Long messageId = resolveMessageId(ctx);
			if (clientId == null || messageId == null) {
				log.warn("client.setAnswerRequired skipped: clientId/messageId is null, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			// Не переносим метку внутри цепочки сообщений, чтобы не сбрасывать таймер ожидания ответа
			Optional<Long> alreadyFlagged = clientService.markAnswerRequiredForSystem(clientId, messageId);
			log.info("AUTO client.setAnswerRequired(clientId={}, messageId={}){}", clientId, messageId,
					alreadyFlagged.map(id -> " skipped: message " + id + " in this chain is already flagged").orElse(""));
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
		// После коммита автоматизации и один раз на заявку, даже если правило поменяло несколько полей
		afterCommitExecutor.runOnce("task:" + taskId,
				() -> webSocketService.taskUpdated(taskService.buildTaskUpdatedDto(null, taskId)));
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

	/** Jev actions exposed to automation rules. */
	public final class JevApi {
		private static final org.slf4j.Logger jevLog = org.slf4j.LoggerFactory.getLogger("JEV");

		private final AutomationExecutionContext ctx;

		JevApi(AutomationExecutionContext ctx) {
			this.ctx = ctx;
		}

		/**
		 * Определяет тип заявки, сервис и линию поддержки по тексту заявки (и связанного сообщения).
		 * Значения, в которых Jev не уверен, не меняются.
		 */
		public void classifyTask() {
			Long taskId = resolveTaskId(ctx);
			if (!ready("jev.classifyTask", taskId == null ? "no task in event" : null)) {
				return;
			}
			String context = "task=" + taskId;
			Task task = taskRepository.findById(taskId).orElseThrow();
			Organization organization = clientRepository.findByTaskId(taskId)
					.map(Client::getOrganization)
					.orElse(null);
			Optional<JevService.Triage> result = jevService.classify(context, taskText(task), organization);
			if (result.isEmpty()) {
				jevLog.info("[{}] jev.classifyTask: no confident decision, task unchanged", context);
				return;
			}
			JevService.Triage triage = result.get();
			ru.ravel.ItDesk.model.Service oldService = task.getService();
			TaskType oldType = task.getType();
			boolean changed = false;

			ru.ravel.ItDesk.model.Service newService = triage.service();
			if (newService != null && !sameEntity(oldService, newService)) {
				task.setService(newService);
				changed = true;
			}
			TaskType newType = triage.type();
			if (newType != null && !sameEntity(oldType, newType)) {
				if (typeAllowedForService(newType, task.getService())) {
					task.setType(newType);
					changed = true;
				} else {
					jevLog.info("[{}] jev.classifyTask: type «{}» skipped, not allowed for service «{}»",
							context, newType.getType(), task.getService().getName());
				}
			}
			if (changed) {
				task.setLastActivity(ZonedDateTime.now());
				taskRepository.save(task);
				if (!sameEntity(oldService, task.getService())) {
					publishTaskEvent(TriggerType.TASK_SERVICE_CHANGED, task, ctx,
							"oldService", oldService,
							"newService", task.getService());
				}
				if (!sameEntity(oldType, task.getType())) {
					publishTaskEvent(TriggerType.TASK_TYPE_CHANGED, task, ctx,
							"oldType", oldType,
							"newType", task.getType());
				}
				notifyTaskUpdated(taskId);
				jevLog.info("[{}] jev.classifyTask: type «{}» -> «{}», service «{}» -> «{}» (rule: {})", context,
						oldType == null ? null : oldType.getType(),
						task.getType() == null ? null : task.getType().getType(),
						oldService == null ? null : oldService.getCode(),
						task.getService() == null ? null : task.getService().getCode(),
						ruleName());
			}
			SupportLine newLine = triage.supportLine();
			SupportLine oldLine = task.getSupportLine();
			if (newLine != null && !sameEntity(oldLine, newLine)) {
				taskRoutingService.routeExistingTask(taskId, newLine.getId(), false, "Jev");
				notifyTaskUpdated(taskId);
				jevLog.info("[{}] jev.classifyTask: support line «{}» -> «{}» (rule: {})", context,
						oldLine == null ? null : oldLine.getName(), newLine.getName(), ruleName());
			} else if (!changed) {
				jevLog.info("[{}] jev.classifyTask: Jev agrees with current values, task unchanged", context);
			}
		}

		/**
		 * Назначает приоритет по тексту заявки. Приоритет остаётся прежним, если Jev не уверен.
		 */
		public void setPriority() {
			Long taskId = resolveTaskId(ctx);
			if (!ready("jev.setPriority", taskId == null ? "no task in event" : null)) {
				return;
			}
			String context = "task=" + taskId;
			Task task = taskRepository.findById(taskId).orElseThrow();
			Priority oldPriority = task.getPriority();
			Optional<Priority> suggested = jevService.suggestPriority(context, taskText(task));
			if (suggested.isEmpty()) {
				jevLog.info("[{}] jev.setPriority: no confident decision, priority unchanged", context);
				return;
			}
			Priority priority = suggested.get();
			if (sameEntity(oldPriority, priority)) {
				jevLog.info("[{}] jev.setPriority: Jev agrees with current priority «{}»", context, priority.getName());
				return;
			}
			new TaskApi(ctx).setPriority(priority.getName());
			jevLog.info("[{}] jev.setPriority: «{}» -> «{}» (rule: {})", context,
					oldPriority == null ? null : oldPriority.getName(), priority.getName(), ruleName());
		}

		/**
		 * Выставляет «нужен ответ» / «ответ не нужен» для входящего сообщения: ответ нужен на вопросы
		 * и проблемы, не нужен на благодарности и подтверждения. В сомнительных случаях флаг не меняется.
		 */
		public void setAnswerRequiredIfNeeded() {
			Long clientId = resolveClientId(ctx);
			Long messageId = resolveMessageId(ctx);
			if (!ready("jev.setAnswerRequiredIfNeeded",
					clientId == null || messageId == null ? "no client or message in event" : null)) {
				return;
			}
			String context = "client=" + clientId + ", message=" + messageId;
			JsonNode text = readByPath(ctx.getEvent().getPayload(), "message.text");
			if (text == null || !text.isTextual()) {
				jevLog.info("[{}] jev.setAnswerRequiredIfNeeded: message has no text, skipped", context);
				return;
			}
			AnswerRequired answer = switch (jevService.needsReply(context, text.asText())) {
				case REQUIRED -> AnswerRequired.ANSWER_REQUIRED;
				case NOT_REQUIRED -> AnswerRequired.ANSWER_NOT_REQUIRED;
				case UNDECIDED -> null;
			};
			if (answer == null) {
				jevLog.info("[{}] jev.setAnswerRequiredIfNeeded: undecided, flag unchanged", context);
				return;
			}
			if (answer == AnswerRequired.ANSWER_REQUIRED) {
				// Метка остаётся на первом сообщении цепочки, чтобы не сбрасывать таймер ожидания ответа
				Optional<Long> alreadyFlagged = clientService.markAnswerRequiredForSystem(clientId, messageId);
				if (alreadyFlagged.isPresent()) {
					jevLog.info("[{}] jev.setAnswerRequiredIfNeeded: ANSWER_REQUIRED, but message {} in this chain "
							+ "is already flagged — flag and timer kept (rule: {})", context, alreadyFlagged.get(), ruleName());
					return;
				}
			} else {
				clientService.answerRequiredForSystem(clientId, messageId, answer);
			}
			jevLog.info("[{}] jev.setAnswerRequiredIfNeeded: {} (rule: {})", context, answer, ruleName());
		}

		/**
		 * Детектор массовых инцидентов для входящего сообщения: Jev размечает сообщение, детектор считает
		 * похожие сбои разных клиентов в окне и, в зависимости от режима, уведомляет ответственных
		 * или создаёт черновик инцидента (см. IncidentDetectorService).
		 */
		public void detectIncident() {
			Long clientId = resolveClientId(ctx);
			Long messageId = resolveMessageId(ctx);
			if (!ready("jev.detectIncident",
					clientId == null || messageId == null ? "no client or message in event" : null)) {
				return;
			}
			String context = "client=" + clientId + ", message=" + messageId;
			JsonNode text = readByPath(ctx.getEvent().getPayload(), "message.text");
			if (text == null || !text.isTextual() || text.asText().isBlank()) {
				jevLog.info("[{}] jev.detectIncident: message has no text, skipped", context);
				return;
			}
			incidentDetectorService.handleIncomingMessage(context, clientId, messageId, text.asText());
		}

		/**
		 * Входящее сообщение: нужна ли новая заявка. Замена правилу «нет открытых заявок» + «текст не спасибо»:
		 * Jev учитывает открытые и недавно закрытые заявки клиента. Без уверенного ответа Jev — прежнее правило.
		 */
		public void createTaskIfNeeded() {
			Long clientId = resolveClientId(ctx);
			Long messageId = resolveMessageId(ctx);
			if (clientId == null || messageId == null) {
				jevLog.warn("jev.createTaskIfNeeded skipped: no client or message in event (rule: {})", ruleName());
				return;
			}
			String context = "client=" + clientId + ", message=" + messageId;
			String text = messageText();
			if (text == null) {
				jevLog.info("[{}] jev.createTaskIfNeeded: message has no text, skipped", context);
				return;
			}
			jevAssistService.createTaskIfNeeded(context, clientId, messageId, text);
		}

		/**
		 * Подбирает статью базы знаний к входящему сообщению (или к заявке) и пишет оператору комментарий в чате.
		 */
		public void suggestKnowledgeArticle() {
			Long clientId = resolveClientId(ctx);
			Long taskId = resolveTaskId(ctx);
			if (!ready("jev.suggestKnowledgeArticle", clientId == null ? "no client in event" : null)) {
				return;
			}
			String text = messageText();
			if (text == null && taskId != null) {
				text = taskRepository.findById(taskId).map(this::taskText).orElse(null);
			}
			String context = "client=" + clientId + (taskId == null ? "" : ", task=" + taskId);
			if (text == null) {
				jevLog.info("[{}] jev.suggestKnowledgeArticle: nothing to analyze", context);
				return;
			}
			jevAssistService.suggestKnowledgeArticle(context, clientId, text);
		}

		/**
		 * Входящее сообщение клиента, чья заявка в инциденте «Наблюдение»/«Восстановлен»:
		 * подтверждение восстановления или сообщение о сохраняющейся проблеме.
		 */
		public void checkIncidentRecovery() {
			Long clientId = resolveClientId(ctx);
			Long messageId = resolveMessageId(ctx);
			if (!ready("jev.checkIncidentRecovery",
					clientId == null || messageId == null ? "no client or message in event" : null)) {
				return;
			}
			String text = messageText();
			if (text == null) {
				return;
			}
			jevAssistService.checkIncidentRecovery("client=" + clientId + ", message=" + messageId, clientId, messageId, text);
		}

		/**
		 * Выбирает линию поддержки для заявки (обычно на «Заявка создана»).
		 */
		public void selectSupportLine() {
			Long taskId = resolveTaskId(ctx);
			if (!ready("jev.selectSupportLine", taskId == null ? "no task in event" : null)) {
				return;
			}
			String context = "task=" + taskId;
			Task task = taskRepository.findById(taskId).orElseThrow();
			jevAssistService.selectSupportLine(context, task).ifPresentOrElse(line -> {
				taskRoutingService.routeExistingTask(taskId, line.getId(), false, "Jev: выбор линии");
				notifyTaskUpdated(taskId);
				jevLog.info("[{}] jev.selectSupportLine: «{}» -> «{}» (rule: {})", context,
						task.getSupportLine() == null ? null : task.getSupportLine().getName(), line.getName(), ruleName());
			}, () -> jevLog.info("[{}] jev.selectSupportLine: line unchanged", context));
		}

		/**
		 * Нужно ли перевести заявку на следующую линию. Работает на событиях заявки (task в событии)
		 * и на входящем сообщении (берётся последняя открытая заявка клиента).
		 */
		public void escalateIfNeeded() {
			Long taskId = resolveTaskId(ctx);
			Long clientId = resolveClientId(ctx);
			if (taskId == null && clientId != null) {
				taskId = clientRepository.findById(clientId)
						.map(Client::getTasks)
						.flatMap(tasks -> tasks.stream()
								.filter(task -> !Boolean.TRUE.equals(task.getCompleted()))
								.max(Comparator.comparing(Task::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))))
						.map(Task::getId)
						.orElse(null);
			}
			if (!ready("jev.escalateIfNeeded", taskId == null ? "no task in event and no open task of the client" : null)) {
				return;
			}
			Long resolvedTaskId = taskId;
			String context = "task=" + resolvedTaskId;
			Task task = taskRepository.findById(resolvedTaskId).orElseThrow();
			Client client = clientRepository.findByTaskId(resolvedTaskId).orElse(null);
			jevAssistService.escalationTarget(context, task, client).ifPresentOrElse(line -> {
				jevAssistService.escalate(context, task, client == null ? null : client.getId(), line,
						"Jev: эскалация (правило " + ruleName() + ")");
				notifyTaskUpdated(resolvedTaskId);
			}, () -> jevLog.info("[{}] jev.escalateIfNeeded: stays on current line", context));
		}

		/**
		 * Новая заявка — дубль открытой? Дубль заявки из активного инцидента привязывается к инциденту,
		 * про прочие дубли оператор получает комментарий.
		 */
		public void findDuplicate() {
			Long taskId = resolveTaskId(ctx);
			if (!ready("jev.findDuplicate", taskId == null ? "no task in event" : null)) {
				return;
			}
			jevAssistService.findDuplicate("task=" + taskId, taskId)
					.ifPresent(match -> notifyTaskUpdated(taskId));
		}

		/*
		 * Те же действия с порогом уверенности, заданным в правиле (число от 0 до 1), например
		 * jev.createTaskIfNeeded(0.7, 0.85). Перекрывает порог из настроек Jev только для этого правила;
		 * null — взять значение из настроек.
		 */

		/** Классификация с порогом threshold для типа, сервиса и линии. */
		public void classifyTask(BigDecimal threshold) {
			withThresholds("jev.classifyTask", JevThresholdOverride.of(JevThreshold.CLASSIFY_TASK,
					ratio("jev.classifyTask", "threshold", threshold)), this::classifyTask);
		}

		/** Приоритет с порогом threshold. */
		public void setPriority(BigDecimal threshold) {
			withThresholds("jev.setPriority", JevThresholdOverride.of(JevThreshold.SET_PRIORITY,
					ratio("jev.setPriority", "threshold", threshold)), this::setPriority);
		}

		/** «Ответ нужен» при вероятности не ниже requiredThreshold, «не нужен» — не выше notRequiredThreshold. */
		public void setAnswerRequiredIfNeeded(BigDecimal requiredThreshold, BigDecimal notRequiredThreshold) {
			String action = "jev.setAnswerRequiredIfNeeded";
			Double required = ratio(action, "requiredThreshold", requiredThreshold);
			Double notRequired = ratio(action, "notRequiredThreshold", notRequiredThreshold);
			if (required != null && notRequired != null && notRequired >= required) {
				throw new IllegalArgumentException(action + ": notRequiredThreshold должен быть меньше requiredThreshold");
			}
			withThresholds(action, new JevThresholdOverride(null, required, notRequired, null), this::setAnswerRequiredIfNeeded);
		}

		/** Детектор инцидентов: считать сбоем при вероятности не ниже outageThreshold. */
		public void detectIncident(BigDecimal outageThreshold) {
			detectIncident(outageThreshold, null);
		}

		/** Детектор инцидентов: порог «это сбой» и порог разбора (сервис, симптом, проверка пачки). */
		public void detectIncident(BigDecimal outageThreshold, BigDecimal analysisThreshold) {
			String action = "jev.detectIncident";
			Double analysis = ratio(action, "analysisThreshold", analysisThreshold);
			withThresholds(action, new JevThresholdOverride(
					analysis == null ? null : Map.of(JevThreshold.INCIDENT_ANALYSIS, analysis),
					null, null, ratio(action, "outageThreshold", outageThreshold)), this::detectIncident);
		}

		/** Создание заявки с порогом threshold (тип сообщения, совпадение с открытой, повтор закрытой). */
		public void createTaskIfNeeded(BigDecimal threshold) {
			createTaskIfNeeded(threshold, null);
		}

		/** Создание заявки: порог решения и порог splitThreshold для разбиения на заявки по сервисам. */
		public void createTaskIfNeeded(BigDecimal threshold, BigDecimal splitThreshold) {
			String action = "jev.createTaskIfNeeded";
			Map<JevThreshold, Double> thresholds = new EnumMap<>(JevThreshold.class);
			Double create = ratio(action, "threshold", threshold);
			Double split = ratio(action, "splitThreshold", splitThreshold);
			if (create != null) thresholds.put(JevThreshold.CREATE_TASK, create);
			if (split != null) thresholds.put(JevThreshold.SPLIT_TASKS, split);
			withThresholds(action, new JevThresholdOverride(thresholds, null, null, null), this::createTaskIfNeeded);
		}

		/** Статья базы знаний с порогом threshold. */
		public void suggestKnowledgeArticle(BigDecimal threshold) {
			withThresholds("jev.suggestKnowledgeArticle", JevThresholdOverride.of(JevThreshold.KNOWLEDGE_ARTICLE,
					ratio("jev.suggestKnowledgeArticle", "threshold", threshold)), this::suggestKnowledgeArticle);
		}

		/** Проверка восстановления после инцидента с порогом threshold. */
		public void checkIncidentRecovery(BigDecimal threshold) {
			withThresholds("jev.checkIncidentRecovery", JevThresholdOverride.of(JevThreshold.INCIDENT_RECOVERY,
					ratio("jev.checkIncidentRecovery", "threshold", threshold)), this::checkIncidentRecovery);
		}

		/** Выбор линии с порогом threshold. */
		public void selectSupportLine(BigDecimal threshold) {
			withThresholds("jev.selectSupportLine", JevThresholdOverride.of(JevThreshold.SUPPORT_LINE,
					ratio("jev.selectSupportLine", "threshold", threshold)), this::selectSupportLine);
		}

		/** Эскалация с порогом threshold. */
		public void escalateIfNeeded(BigDecimal threshold) {
			withThresholds("jev.escalateIfNeeded", JevThresholdOverride.of(JevThreshold.ESCALATION,
					ratio("jev.escalateIfNeeded", "threshold", threshold)), this::escalateIfNeeded);
		}

		/** Поиск дублей с порогом threshold. */
		public void findDuplicate(BigDecimal threshold) {
			withThresholds("jev.findDuplicate", JevThresholdOverride.of(JevThreshold.DUPLICATE,
					ratio("jev.findDuplicate", "threshold", threshold)), this::findDuplicate);
		}

		private Double ratio(String action, String name, BigDecimal value) {
			if (value == null) {
				return null;
			}
			double ratio = value.doubleValue();
			if (Double.isNaN(ratio) || ratio < 0 || ratio > 1) {
				throw new IllegalArgumentException("%s: %s должен быть числом от 0 до 1, указано %s"
						.formatted(action, name, value.toPlainString()));
			}
			return ratio;
		}

		private void withThresholds(String action, JevThresholdOverride override, Runnable run) {
			if (!override.isEmpty()) {
				jevLog.info("{}: thresholds from rule «{}»: {}", action, ruleName(), override);
			}
			override.apply(run);
		}

		private String messageText() {
			JsonNode text = readByPath(ctx.getEvent().getPayload(), "message.text");
			return text == null || !text.isTextual() || text.asText().isBlank() ? null : text.asText();
		}

		/**
		 * Общие проверки перед действием; причину пропуска пишет в лог.
		 */
		private boolean ready(String action, String missingReason) {
			if (missingReason != null) {
				jevLog.warn("{} skipped: {} (rule: {}, event: {})", action, missingReason, ruleName(), safeEventInfo(ctx.getEvent()));
				return false;
			}
			if (!jevService.isEnabled()) {
				jevLog.debug("{} skipped: Jev disabled or API key is not set (rule: {})", action, ruleName());
				return false;
			}
			return true;
		}

		private String ruleName() {
			return ctx.getTrigger() == null ? "-" : "#" + ctx.getTrigger().getId() + " «" + ctx.getTrigger().getName() + "»";
		}

		private String taskText(Task task) {
			StringBuilder text = new StringBuilder(Objects.toString(task.getName(), ""));
			if (task.getDescription() != null && !task.getDescription().isBlank()) {
				text.append("\n").append(task.getDescription());
			}
			if (task.getLinkedMessageId() != null) {
				messageRepository.findById(task.getLinkedMessageId())
						.map(Message::getText)
						.filter(linked -> linked != null && !linked.isBlank())
						.ifPresent(linked -> text.append("\n").append(linked));
			}
			return text.toString().trim();
		}

		private boolean typeAllowedForService(TaskType type, ru.ravel.ItDesk.model.Service service) {
			if (service == null || service.getAllowedTaskTypes() == null || service.getAllowedTaskTypes().isEmpty()) {
				return true;
			}
			return service.getAllowedTaskTypes().stream().anyMatch(allowed -> sameEntity(allowed, type));
		}
	}

	public final class WebhookApi {
		private final AutomationExecutionContext ctx;

		WebhookApi(AutomationExecutionContext ctx) {
			this.ctx = ctx;
		}

		public void call(String url, Object body) {
			deliver(url, body, Map.of());
		}

		public void callWithHeaders(String url, Object body, Object headers) {
			deliver(url, body, webhookHeaders(headers));
		}

		private void deliver(String url, Object body, Map<String, String> headers) {
			if (url == null || url.isBlank()) {
				log.warn("webhook.call skipped: url is blank, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}

			var result = webhookService.call(url, body, headers);
			if (!result.successful()) {
				throw new WebhookDeliveryException(
						"Webhook вернул HTTP " + result.statusCode() + ": " + result.responseBody()
				);
			}
			log.info("AUTO webhook.call(url={}, status={}, durationMs={})",
					url, result.statusCode(), result.durationMs());
		}

		private Map<String, String> webhookHeaders(Object raw) {
			if (raw == null) {
				return Map.of();
			}
			Map<String, String> result = new LinkedHashMap<>();
			if (raw instanceof Map<?, ?> map) {
				map.forEach((name, value) -> {
					if (name != null && value != null) {
						result.put(String.valueOf(name), asString(value));
					}
				});
				return result;
			}
			if (raw instanceof JsonNode node && node.isObject()) {
				node.fields().forEachRemaining(entry ->
						result.put(entry.getKey(), asString(entry.getValue()))
			);
				return result;
			}
			throw new IllegalArgumentException("Заголовки webhook должны быть JSON-объектом");
		}
	}

	// ======================================================================
	// INVOKE (рефлексия) — чтобы actionType работал как вызов метода объекта
	// ======================================================================

	private void invokeJevAction(Object target, String methodName, List<Object> rawArgs, AutomationExecutionContext ctx)
			throws Exception {
		Exception[] failure = new Exception[1];
		AutomationTrigger trigger = ctx.getTrigger();
		JevDecisionRecorder.inAction(
				new JevDecisionRecorder.ActionContext(
						trigger == null ? null : trigger.getId(),
						trigger == null ? null : trigger.getName(),
						methodName),
				() -> {
					try {
						invoke(target, methodName, rawArgs);
					} catch (Exception e) {
						failure[0] = e;
					}
				});
		if (failure[0] != null) {
			throw failure[0];
		}
	}

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

	private Long resolveMessageId(AutomationExecutionContext ctx) {
		Event e = ctx.getEvent();
		if (e == null) {
			return null;
		}
		return readLong(e.getPayload(), "message.id");
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
