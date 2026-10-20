package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.POJONode;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.AnswerRequired;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.dto.MonitoringProblemEvent;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.model.incident.IncidentSeverity;
import ru.ravel.ItDesk.model.incident.IncidentSignal;
import ru.ravel.ItDesk.model.incident.IncidentSignalDecision;
import ru.ravel.ItDesk.model.incident.SupportIncident;
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
	private final ServiceRepository serviceRepository;
	private final ServiceCatalogService serviceCatalogService;
	private final TaskTypeRepository taskTypeRepository;
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
	private final IncidentService incidentService;
	private final ru.ravel.ItDesk.component.AfterCommitExecutor afterCommitExecutor;
	private final MessageRepository messageRepository;
	private final ClientRepository clientRepository;
	private final MonitoringIncidentService monitoringIncidentService;

	/**
	 * Приходит из AutomationScriptRuntime:
	 * <p>
	 * actionType = "client.sendMessage"
	 * actionNode = { "args": ["Принято!"] }
	 *
	 * @return то, что вернул метод (результат шага: объект, число, null); скрипт правила кладёт его
	 * в переменную записью {@code var.имя = target.method(...)}
	 */
	public Object execute(String actionType, JsonNode actionNode, AutomationExecutionContext ctx) {
		if (actionType == null || actionType.isBlank() || ctx == null) {
			return null;
		}
		List<Object> args = parseArgs(actionNode);
		ActionCall call = ActionCall.parse(actionType);
		if (call == null) {
			log.warn("Invalid actionType='{}'", actionType);
			return null;
		}
		AutomationApi api = new AutomationApi(ctx);
		Object target = api.resolveTarget(call.target);
		if (target == null) {
			log.warn("Unknown target '{}', actionType='{}'", call.target, actionType);
			return null;
		}
		try {
			if (target instanceof JevApi) {
				// журнал решений Jev знает, какое правило и действие спросили Jev
				return invokeJevAction(target, call.method, args, ctx);
			}
			return invoke(target, call.method, args);
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

		public IncidentApi incident() {
			return new IncidentApi(ctx);
		}

		@Nullable
		Object resolveTarget(String name) {
			return switch (name) {
				case "client" -> client();
				case "task" -> task();
				case "notify" -> notifyApi();
				case "webhook" -> webhook();
				case "jev" -> jev();
				case "incident" -> incident();
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

		public void setAnswerRequired(Boolean required) {
			Long clientId = resolveClientId(ctx), messageId = resolveMessageId(ctx);
			if (clientId == null || messageId == null) throw new IllegalArgumentException("Нужны клиент и сообщение");
			if (Boolean.TRUE.equals(required)) clientService.markAnswerRequiredForSystem(clientId, messageId);
			else clientService.answerRequiredForSystem(clientId, messageId, AnswerRequired.ANSWER_NOT_REQUIRED);
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

		/** Внутренний комментарий в чате клиента — виден только сотрудникам, клиент его не получает. */
		public void addInternalNote(String text) {
			Long clientId = resolveClientId(ctx);
			if (clientId == null) {
				log.warn("client.addInternalNote skipped: clientId is null, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			if (text == null || text.isBlank()) {
				log.warn("client.addInternalNote skipped: text is blank, clientId={}", clientId);
				return;
			}
			Message message = Message.builder()
					.text(text)
					.isSent(true)
					.isComment(true)
					.isRead(true)
					.date(ZonedDateTime.now())
					.build();
			clientService.sendMessageForSystem(clientId, message);
			log.info("AUTO client.addInternalNote(clientId={}, text={})", clientId, text);
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

		public void setService(Long serviceId) {
			Task task = taskRepository.findById(resolveTaskId(ctx)).orElseThrow();
			ru.ravel.ItDesk.model.Service previous = task.getService();
			ru.ravel.ItDesk.model.Service service = serviceRepository.findById(serviceId).orElseThrow();
			Organization organization = clientRepository.findByTaskId(task.getId()).map(Client::getOrganization).orElse(null);
			// Availability and type compatibility are invariants, independent of graph policy.
			if (serviceCatalogService.getAvailableServices(organization == null ? null : organization.getId()).stream()
					.noneMatch(a -> Objects.equals(((Number) a.get("id")).longValue(), serviceId))) {
				throw new IllegalArgumentException("Сервис недоступен организации клиента");
			}
			if (sameEntity(previous, service)) return;
			task.setService(service); task.setLastActivity(ZonedDateTime.now()); taskRepository.save(task);
			publishTaskEvent(TriggerType.TASK_SERVICE_CHANGED, task, ctx, "oldService", previous, "newService", service);
			notifyTaskUpdated(task.getId());
		}

		public void setType(Long typeId) {
			Task task = taskRepository.findById(resolveTaskId(ctx)).orElseThrow();
			TaskType previous = task.getType(), type = taskTypeRepository.findById(typeId).orElseThrow();
			if (task.getService() != null && task.getService().getAllowedTaskTypes() != null
					&& !task.getService().getAllowedTaskTypes().isEmpty()
					&& task.getService().getAllowedTaskTypes().stream().noneMatch(t -> Objects.equals(t.getId(), typeId))) {
				throw new IllegalArgumentException("Тип заявки не разрешён для сервиса");
			}
			if (sameEntity(previous, type)) return;
			task.setType(type); task.setLastActivity(ZonedDateTime.now()); taskRepository.save(task);
			publishTaskEvent(TriggerType.TASK_TYPE_CHANGED, task, ctx, "oldType", previous, "newType", type);
			notifyTaskUpdated(task.getId());
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
				jevLog.debug("[{}] jev.classifyTask: no confident decision, task unchanged", context);
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
					jevLog.debug("[{}] jev.classifyTask: type «{}» skipped, not allowed for service «{}»",
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
				jevLog.debug("[{}] jev.classifyTask: type «{}» -> «{}», service «{}» -> «{}» (rule: {})", context,
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
				jevLog.debug("[{}] jev.classifyTask: support line «{}» -> «{}» (rule: {})", context,
						oldLine == null ? null : oldLine.getName(), newLine.getName(), ruleName());
			} else if (!changed) {
				jevLog.debug("[{}] jev.classifyTask: Jev agrees with current values, task unchanged", context);
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
				jevLog.debug("[{}] jev.setPriority: no confident decision, priority unchanged", context);
				return;
			}
			Priority priority = suggested.get();
			if (sameEntity(oldPriority, priority)) {
				jevLog.debug("[{}] jev.setPriority: Jev agrees with current priority «{}»", context, priority.getName());
				return;
			}
			new TaskApi(ctx).setPriority(priority.getName());
			jevLog.debug("[{}] jev.setPriority: «{}» -> «{}» (rule: {})", context,
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
				jevLog.debug("[{}] jev.setAnswerRequiredIfNeeded: message has no text, skipped", context);
				return;
			}
			AnswerRequired answer = switch (jevService.needsReply(context, text.asText())) {
				case REQUIRED -> AnswerRequired.ANSWER_REQUIRED;
				case NOT_REQUIRED -> AnswerRequired.ANSWER_NOT_REQUIRED;
				case UNDECIDED -> null;
			};
			if (answer == null) {
				jevLog.debug("[{}] jev.setAnswerRequiredIfNeeded: undecided, flag unchanged", context);
				return;
			}
			if (answer == AnswerRequired.ANSWER_REQUIRED) {
				// Метка остаётся на первом сообщении цепочки, чтобы не сбрасывать таймер ожидания ответа
				Optional<Long> alreadyFlagged = clientService.markAnswerRequiredForSystem(clientId, messageId);
				if (alreadyFlagged.isPresent()) {
					jevLog.debug("[{}] jev.setAnswerRequiredIfNeeded: ANSWER_REQUIRED, but message {} in this chain "
							+ "is already flagged — flag and timer kept (rule: {})", context, alreadyFlagged.get(), ruleName());
					return;
				}
			} else {
				clientService.answerRequiredForSystem(clientId, messageId, answer);
			}
			jevLog.debug("[{}] jev.setAnswerRequiredIfNeeded: {} (rule: {})", context, answer, ruleName());
		}

		/**
		 * Шаг детектора массовых инцидентов: Jev размечает входящее сообщение (сбой ли это, какой сервис и симптом,
		 * относится ли к открытому инциденту). Только разметка: результат правило кладёт в переменную сама,
		 * {@code var.outage = jev.analyzeOutage()}, а что делать дальше (посчитать обращения, сравнить с порогом,
		 * создать инцидент) решают следующие ноды. Результат: {@code signalId}, {@code probability},
		 * {@code serviceId}, {@code serviceName}, {@code symptom}, {@code organizationId}, {@code monitoringStatus},
		 * {@code matchedIncidentId}, {@code matchedIncidentNumber}; null — Jev не ответил.
		 */
		public Map<String, Object> analyzeOutage() {
			Long clientId = resolveClientId(ctx);
			Long messageId = resolveMessageId(ctx);
			if (!ready("jev.analyzeOutage",
					clientId == null || messageId == null ? "no client or message in event" : null)) {
				return null;
			}
			String context = "client=" + clientId + ", message=" + messageId;
			String text = messageText();
			if (text == null) {
				jevLog.debug("[{}] jev.analyzeOutage: message has no text, skipped", context);
				return null;
			}
			return incidentDetectorService.analyze(context, clientId, messageId, text).map(analysis -> {
				IncidentSignal signal = analysis.signal();
				JevService.OutageSignal outage = analysis.outage();
				ru.ravel.ItDesk.model.Service service = outage.service();
				SupportIncident matched = outage.matchedIncident();
				return resultOf(
						"signalId", signal.getId(),
						"probability", outage.outageProbability(),
						"serviceId", service == null ? null : service.getId(),
						"serviceName", service == null ? null : service.getName(),
						"symptom", outage.symptom(),
						"organizationId", signal.getOrganizationId(),
						"monitoringStatus", service == null || service.getMonitoringStatus() == null
								? null : service.getMonitoringStatus().name(),
						"matchedIncidentId", matched == null ? null : matched.getId(),
						"matchedIncidentNumber", matched == null ? null : matched.getIncidentNumber());
			}).orElse(null);
		}

		/**
		 * Шаг детектора: Jev проверяет, что сообщения окна ({@code window} — результат {@code incident.countSignals})
		 * и текущее описывают одну проблему: {@code var.verdict = jev.verifyBatch(var.outage.signalId, var.window)}.
		 * Результат: {@code sameProblem} (вероятность 0..1), {@code severity} (P1..P4 по оценке Jev),
		 * {@code severityScore}, {@code cause}; null — проверка недоступна (Jev не ответил, меньше двух сообщений).
		 */
		public Map<String, Object> verifyBatch(Long signalId, JsonNode window) {
			if (!ready("jev.verifyBatch", signalId == null || window == null ? "signalId and window are required" : null)) {
				return null;
			}
			String context = "signal=" + signalId;
			String text = messageText();
			if (text == null) {
				jevLog.debug("[{}] jev.verifyBatch: message has no text, skipped", context);
				return null;
			}
			return incidentDetectorService.verifyBatch(context, longsOf(window.path("signalIds")), text)
					.map(verdict -> resultOf(
							"sameProblem", verdict.sameProblemProbability(),
							"severity", verdict.severity().name(),
							"severityScore", verdict.severityScore(),
							"cause", verdict.probableCause()))
					.orElse(null);
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
				jevLog.debug("[{}] jev.createTaskIfNeeded: message has no text, skipped", context);
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
				jevLog.debug("[{}] jev.suggestKnowledgeArticle: nothing to analyze", context);
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
				jevLog.debug("[{}] jev.selectSupportLine: «{}» -> «{}» (rule: {})", context,
						task.getSupportLine() == null ? null : task.getSupportLine().getName(), line.getName(), ruleName());
			}, () -> jevLog.debug("[{}] jev.selectSupportLine: line unchanged", context));
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
			}, () -> jevLog.debug("[{}] jev.escalateIfNeeded: stays on current line", context));
		}

		/**
		 * Новая заявка — дубль открытой? Только решение: {@code var.duplicate = jev.findDuplicate()}. Результат:
		 * {@code taskId} дубля, {@code incidentId} и {@code incidentNumber} (если дубль в активном инциденте),
		 * {@code sameClient}; null — дубля нет. Привязку к инциденту и комментарий оператору делает само правило
		 * через condition/action-ноды ({@code incident.linkTask}, {@code incident.addNote}, {@code client.addInternalNote}).
		 */
		public Map<String, Object> findDuplicate() {
			Long taskId = resolveTaskId(ctx);
			if (!ready("jev.findDuplicate", taskId == null ? "no task in event" : null)) {
				return null;
			}
			return jevAssistService.findDuplicate("task=" + taskId, taskId)
					.map(match -> resultOf(
							"taskId", match.duplicateOfTaskId(),
							"incidentId", match.incident() == null ? null : match.incident().getId(),
							"incidentNumber", match.incident() == null ? null : match.incident().getIncidentNumber(),
							"sameClient", match.sameClient()))
					.orElse(null);
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
			withThresholds(action, new JevThresholdOverride(null, required, notRequired), this::setAnswerRequiredIfNeeded);
		}

		/** Разметка сообщения детектором с порогом уверенности выбора (сервис, симптом, связь с открытым инцидентом). */
		public Map<String, Object> analyzeOutage(BigDecimal analysisThreshold) {
			return withThresholdsReturning("jev.analyzeOutage", JevThresholdOverride.of(JevThreshold.INCIDENT_ANALYSIS,
					ratio("jev.analyzeOutage", "analysisThreshold", analysisThreshold)), this::analyzeOutage);
		}

		/** Проверка пачки с порогом уверенности выбора вероятной причины. */
		public Map<String, Object> verifyBatch(Long signalId, JsonNode window, BigDecimal analysisThreshold) {
			return withThresholdsReturning("jev.verifyBatch", JevThresholdOverride.of(JevThreshold.INCIDENT_ANALYSIS,
					ratio("jev.verifyBatch", "analysisThreshold", analysisThreshold)), () -> verifyBatch(signalId, window));
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
			withThresholds(action, new JevThresholdOverride(thresholds, null, null), this::createTaskIfNeeded);
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
		public Map<String, Object> findDuplicate(BigDecimal threshold) {
			return withThresholdsReturning("jev.findDuplicate", JevThresholdOverride.of(JevThreshold.DUPLICATE,
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
			withThresholdsReturning(action, override, () -> {
				run.run();
				return null;
			});
		}

		private <T> T withThresholdsReturning(String action, JevThresholdOverride override, java.util.function.Supplier<T> run) {
			if (!override.isEmpty()) {
				jevLog.debug("{}: thresholds from rule «{}»: {}", action, ruleName(), override);
			}
			return override.apply(run);
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

	/**
	 * "incident.*" — инциденты по событиям автоматизаций.
	 */
	public final class IncidentApi {
		private static final org.slf4j.Logger jevLog = org.slf4j.LoggerFactory.getLogger("JEV");
		private static final com.fasterxml.jackson.databind.ObjectMapper PAYLOAD_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper()
				.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		private final AutomationExecutionContext ctx;

		IncidentApi(AutomationExecutionContext ctx) {
			this.ctx = ctx;
		}

		/**
		 * События мониторинга Zabbix (Мониторинг: проблема / обновлена / устранена): проблема по сервису создаёт черновик
		 * инцидента или привязывается к активному инциденту сервиса, а когда открытых проблем не осталось —
		 * инцидент переводится в «Наблюдение» для проверки восстановления. Без сопоставленного сервиса ничего не делает.
		 */
		public void fromMonitoring() {
			Event event = ctx.getEvent();
			TriggerType trigger = event == null ? null : event.getTriggerType();
			if (trigger == null || event.getPayload() == null) {
				log.warn("incident.fromMonitoring skipped: no monitoring event (rule: {})", ruleName());
				return;
			}
			MonitoringProblemEvent payload;
			try {
				payload = PAYLOAD_MAPPER.treeToValue(event.getPayload(), MonitoringProblemEvent.class);
			} catch (Exception e) {
				log.warn("incident.fromMonitoring skipped: payload is not a monitoring event: {}", e.getMessage());
				return;
			}
			MonitoringIncidentService.Result result = monitoringIncidentService.handle(trigger, payload);
			log.info("incident.fromMonitoring: {} {} — {} (rule: {})", trigger, result.outcome(), result.details(), ruleName());
		}

		// ---- Детектор массовых инцидентов: шаги над сигналом из jev.analyzeOutage(). Шаги ничего не читают и не
		// ---- пишут неявно: всё нужное приходит аргументами (var.* правила), результат правило кладёт в переменную.

		/** {@code var.window = incident.countSignals(var.outage.signalId, 15)}: окно в минутах, клиенты всех организаций. */
		public Map<String, Object> countSignals(Long signalId, Integer windowMinutes) {
			return countSignals(signalId, windowMinutes, null);
		}

		/**
		 * Сколько обращений и разных клиентов было о том же сервисе и симптоме за {@code windowMinutes} до обращения
		 * {@code signalId}. {@code scope}: {@code 'all'} (по умолчанию) — клиенты всех организаций,
		 * {@code 'organization'} — только организации текущего клиента. Сохранённое в старых графах
		 * {@code 'GLOBAL'} принимается как синоним {@code 'all'}. Результат: {@code clients}, {@code messages},
		 * {@code organizations}, {@code signalIds} (обращения окна), {@code windowMinutes}; null — сигнал не найден.
		 */
		public Map<String, Object> countSignals(Long signalId, Integer windowMinutes, String scope) {
			IncidentSignal signal = signal("incident.countSignals", signalId);
			if (signal == null) {
				return null;
			}
			if (windowMinutes == null || windowMinutes < 1) {
				throw new IllegalArgumentException("incident.countSignals: окно должно быть не меньше 1 минуты");
			}
			boolean sameOrganizationOnly;
			if (scope == null || "all".equalsIgnoreCase(scope.trim()) || "global".equalsIgnoreCase(scope.trim())) {
				sameOrganizationOnly = false;
			} else if ("organization".equalsIgnoreCase(scope.trim())) {
				sameOrganizationOnly = true;
			} else {
				throw new IllegalArgumentException("incident.countSignals: область '" + scope + "' неизвестна (допустимы 'all', 'organization')");
			}
			IncidentDetectorService.WindowCount count = incidentDetectorService.count(signal, windowMinutes, sameOrganizationOnly);
			jevLog.debug("AUTO incident.countSignals(window={} min, scope={}) -> {} client(s), {} message(s), {} organization(s) (rule: {})",
					windowMinutes, sameOrganizationOnly ? "organization" : "all", count.clients().size(), count.messages(),
					count.organizations().size(), ruleName());
			return resultOf(
					"clients", count.clients().size(),
					"messages", count.messages(),
					"organizations", count.organizations().size(),
					"signalIds", count.window().stream().map(IncidentSignal::getId).toList(),
					"windowMinutes", windowMinutes);
		}

		/**
		 * Было ли за последние {@code minutes} срабатывание по тому же сервису и симптому (0 — пауза выключена).
		 * С {@code onlyWithIncident = true} считаются только срабатывания, по которым создан инцидент.
		 * Результат: {@code active} и {@code incidentId} (активный инцидент того срабатывания); null — сигнал не найден.
		 */
		public Map<String, Object> checkCooldown(Long signalId, Integer minutes, Boolean onlyWithIncident) {
			IncidentSignal signal = signal("incident.checkCooldown", signalId);
			if (signal == null) {
				return null;
			}
			IncidentDetectorService.Cooldown cooldown = incidentDetectorService.cooldown(
					signal, minutes == null ? 0 : minutes, Boolean.TRUE.equals(onlyWithIncident));
			return resultOf("active", cooldown.active(), "incidentId", cooldown.incidentId());
		}

		/** Записывает решение по сигналу: IGNORED, COUNTED, MATCHED_INCIDENT, TRIGGERED, BATCH_REJECTED, COOLDOWN. */
		public void markSignal(Long signalId, String decision) {
			markSignal(signalId, decision, null);
		}

		/** То же, плюс инцидент, к которому относится сигнал (без привязки заявки). */
		public void markSignal(Long signalId, String decision, Long incidentId) {
			IncidentSignal signal = signal("incident.markSignal", signalId);
			if (signal == null) {
				return;
			}
			IncidentSignalDecision parsed;
			try {
				parsed = IncidentSignalDecision.valueOf(Objects.toString(decision, "").trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("incident.markSignal: неизвестное решение '" + decision + "' (допустимы "
						+ Arrays.toString(IncidentSignalDecision.values()) + ")");
			}
			incidentDetectorService.mark(signal, parsed, incidentId);
			jevLog.debug("AUTO incident.markSignal({}, incidentId={}) (rule: {})", parsed, incidentId, ruleName());
		}

		/**
		 * Относит сообщение к открытому инциденту: привязывает заявку клиента и пишет запись в таймлайн.
		 * Заявка ищется по сообщению, иначе — последняя открытая заявка клиента за {@code lookbackMinutes}.
		 */
		public void attachSignal(Long signalId, Long incidentId, Integer lookbackMinutes) {
			IncidentSignal signal = signal("incident.attachSignal", signalId);
			if (signal == null) {
				return;
			}
			if (incidentId == null) {
				log.warn("incident.attachSignal skipped: incidentId is null (rule: {})", ruleName());
				return;
			}
			incidentDetectorService.attach("signal=" + signal.getId(), signal, incidentId, lookbackMinutes == null ? 0 : lookbackMinutes);
		}

		/** Черновик с интервалом обновлений инцидента по умолчанию. */
		public Map<String, Object> createDraftFromSignals(Long signalId, JsonNode window, JsonNode verdict, String severity) {
			return createDraftFromSignals(signalId, window, verdict, severity, null, null, null, null);
		}

		/**
		 * Создаёт черновик инцидента по пачке ({@code window} — результат {@code incident.countSignals}, {@code verdict} —
		 * результат {@code jev.verifyBatch}) с серьёзностью {@code 'P1'}…{@code 'P4'}, привязывает заявки и отмечает
		 * сигналы как TRIGGERED. {@code pNInterval} — интервал обновлений инцидента в минутах для серьёзности.
		 * Результат: {@code id} и {@code number} созданного инцидента.
		 */
		public Map<String, Object> createDraftFromSignals(Long signalId, JsonNode window, JsonNode verdict, String severity,
		                                                 Integer p1Interval, Integer p2Interval, Integer p3Interval, Integer p4Interval) {
			IncidentSignal signal = signal("incident.createDraftFromSignals", signalId);
			if (signal == null) {
				return null;
			}
			if (window == null || verdict == null || !window.hasNonNull("windowMinutes") || !verdict.hasNonNull("sameProblem")) {
				log.warn("incident.createDraftFromSignals skipped: window (incident.countSignals) and verdict (jev.verifyBatch) are required (rule: {})", ruleName());
				return null;
			}
			JevService.BatchVerdict batchVerdict = new JevService.BatchVerdict(verdict.path("sameProblem").asDouble(),
					IncidentDetectorService.severityOf(verdict.path("severity").asText()),
					verdict.path("severityScore").asDouble(), verdict.path("cause").asText("неизвестно"));
			Map<IncidentSeverity, Integer> intervals = new EnumMap<>(IncidentSeverity.class);
			if (p1Interval != null) intervals.put(IncidentSeverity.P1, p1Interval);
			if (p2Interval != null) intervals.put(IncidentSeverity.P2, p2Interval);
			if (p3Interval != null) intervals.put(IncidentSeverity.P3, p3Interval);
			if (p4Interval != null) intervals.put(IncidentSeverity.P4, p4Interval);
			SupportIncident incident = incidentDetectorService.createDraft("signal=" + signal.getId(), signal,
					incidentDetectorService.findSignals(longsOf(window.path("signalIds"))), batchVerdict,
					IncidentDetectorService.severityOf(Objects.toString(severity, "")), window.path("windowMinutes").asInt(), intervals);
			jevLog.debug("AUTO incident.createDraftFromSignals({}) -> {} (rule: {})", severity, incident.getIncidentNumber(), ruleName());
			return resultOf("id", incident.getId(), "number", incident.getIncidentNumber());
		}

		/**
		 * Уведомляет ответственных за сервис (владелец и исполнители сервиса, ответственный и участники линии;
		 * если таких нет — администраторы) через стандартные уведомления; {@code window} — результат
		 * {@code incident.countSignals}.
		 */
		public void notifyResponsible(Long signalId, JsonNode window, String text) {
			IncidentSignal signal = signal("incident.notifyResponsible", signalId);
			if (signal == null) {
				return;
			}
			if (window == null || text == null || text.isBlank()) {
				log.warn("incident.notifyResponsible skipped: window or text is missing (rule: {})", ruleName());
				return;
			}
			incidentDetectorService.notifyResponsible("signal=" + signal.getId(), signal,
					incidentDetectorService.findSignals(longsOf(window.path("signalIds"))),
					window.path("windowMinutes").asInt(0), text);
		}

		/** Привязывает заявку из события (например, {@code task.id} из TASK_CREATED) к инциденту. */
		public void linkTask(Long incidentId) {
			Long taskId = resolveTaskId(ctx);
			if (incidentId == null || taskId == null) {
				log.warn("incident.linkTask skipped: incidentId/taskId is null, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			List<Long> linked = incidentService.linkTasksForSystem(incidentId, List.of(taskId));
			log.info("AUTO incident.linkTask(incidentId={}, taskId={}) -> {} (rule: {})", incidentId, taskId,
					linked.isEmpty() ? "already linked or not found" : "linked", ruleName());
			if (!linked.isEmpty()) {
				notifyTaskUpdated(taskId);
			}
		}

		/** Системная заметка в ленте инцидента (видна только сотрудникам, не клиенту). */
		public void addNote(Long incidentId, String text) {
			if (incidentId == null || text == null || text.isBlank()) {
				log.warn("incident.addNote skipped: incidentId is null or text is blank, event={}", safeEventInfo(ctx.getEvent()));
				return;
			}
			incidentService.addSystemNote(incidentId, text);
			log.info("AUTO incident.addNote(incidentId={}, text={})", incidentId, text);
		}

		private IncidentSignal signal(String action, Long signalId) {
			Optional<IncidentSignal> signal = incidentDetectorService.findSignal(signalId);
			if (signal.isEmpty()) {
				log.warn("{} skipped: signal {} not found, pass var.outage.signalId from jev.analyzeOutage() (rule: {})",
						action, signalId, ruleName());
			}
			return signal.orElse(null);
		}

		private String ruleName() {
			return ctx.getTrigger() == null ? "-" : "#" + ctx.getTrigger().getId() + " «" + ctx.getTrigger().getName() + "»";
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

	private Object invokeJevAction(Object target, String methodName, List<Object> rawArgs, AutomationExecutionContext ctx)
			throws Exception {
		Exception[] failure = new Exception[1];
		Object[] result = new Object[1];
		AutomationTrigger trigger = ctx.getTrigger();
		JevDecisionRecorder.inAction(
				new JevDecisionRecorder.ActionContext(
						trigger == null ? null : trigger.getId(),
						trigger == null ? null : trigger.getName(),
						methodName, ctx.getEvent() == null ? null : ctx.getEvent().getId()),
				() -> {
					try {
						result[0] = invoke(target, methodName, rawArgs);
					} catch (Exception e) {
						failure[0] = e;
					}
				});
		if (failure[0] != null) {
			throw failure[0];
		}
		return result[0];
	}

	private Object invoke(Object target, String methodName, List<Object> rawArgs) throws Exception {
		// Критичные системные действия вызываем явно. Это исключает зависимость
		// от порядка/видимости методов reflection и одинаково принимает числа,
		// пришедшие от Jackson как Integer, Long, BigDecimal и другие Number.
		if (invokeKnownAction(target, methodName, rawArgs)) {
			return null;
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
			return m.invoke(target, converted);
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

	/** Результат шага для переменной правила: пары имя-значение (значения могут быть null). */
	private static Map<String, Object> resultOf(Object... pairs) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			result.put((String) pairs[i], pairs[i + 1]);
		}
		return result;
	}

	private static List<Long> longsOf(JsonNode array) {
		List<Long> result = new ArrayList<>();
		if (array != null && array.isArray()) {
			array.forEach(item -> result.add(item.asLong()));
		}
		return result;
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
