package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AnswerRequired;
import ru.ravel.ItDesk.dto.UserNotificationHistoryDto;
import ru.ravel.ItDesk.dto.UserNotificationHistoryPageDto;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.SlaPauseRepository;
import ru.ravel.ItDesk.repository.TaskRepository;
import ru.ravel.ItDesk.repository.UserNotificationHistoryRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


@Service
@RequiredArgsConstructor
public class UserNotificationService {

	private final UserRepository userRepository;
	private final ClientRepository clientRepository;
	private final WebSocketService webSocketService;
	private final TaskRepository taskRepository;
	private final SlaPauseRepository slaPauseRepository;
	private final UserNotificationHistoryRepository notificationHistoryRepository;
	private final AnswerWaitingTimeService answerWaitingTimeService;
	private final AppSettingsService appSettingsService;

	private final Set<String> sentNotificationKeys = ConcurrentHashMap.newKeySet();


	@Transactional
	public void send(UserNotification notification) {
		if (notification == null || notification.getUserId() == null || notification.getEvent() == null) {
			return;
		}

		userRepository.findById(notification.getUserId())
				.filter(user -> isNotificationEnabled(user, notification.getEvent()))
				.ifPresent(user -> {
					String title = "ULDesk";
					String body = buildNotificationBody(notification);
					UserNotificationHistory saved = notificationHistoryRepository.save(
							UserNotificationHistory.builder()
									.userId(user.getId())
									.event(notification.getEvent())
									.title(title)
									.body(body)
									.sourceMessage(notification.getMessage())
									.createdAt(ZonedDateTime.now())
									.build()
					);
					webSocketService.userNotification(user.getUsername(), toDto(saved));
				});
	}


	@Transactional(readOnly = true)
	public UserNotificationHistoryPageDto getCurrentUserNotifications(int page, int size, boolean unreadOnly) {
		User user = getAuthenticatedUser();
		int safePage = Math.max(1, page);
		int safeSize = Math.min(100, Math.max(1, size));
		PageRequest pageable = PageRequest.of(
				safePage - 1,
				safeSize,
				Sort.by(Sort.Direction.DESC, "createdAt")
		);
		Page<UserNotificationHistory> result = unreadOnly
				? notificationHistoryRepository.findAllByUserIdAndReadAtIsNullOrderByCreatedAtDesc(user.getId(), pageable)
				: notificationHistoryRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId(), pageable);

		return UserNotificationHistoryPageDto.builder()
				.notifications(result.getContent().stream().map(this::toDto).toList())
				.totalElements(result.getTotalElements())
				.totalPages(result.getTotalPages())
				.page(safePage)
				.size(safeSize)
				.unreadCount(notificationHistoryRepository.countByUserIdAndReadAtIsNull(user.getId()))
				.end(result.isLast())
				.build();
	}


	@Transactional
	public UserNotificationHistoryDto markCurrentUserNotificationAsRead(Long notificationId) {
		if (notificationId == null) {
			throw new IllegalArgumentException("notification id is required");
		}
		User user = getAuthenticatedUser();
		UserNotificationHistory notification = notificationHistoryRepository
				.findByIdAndUserId(notificationId, user.getId())
				.orElseThrow(() -> new IllegalArgumentException("Уведомление не найдено"));
		if (notification.getReadAt() == null) {
			notification.setReadAt(ZonedDateTime.now());
			notification = notificationHistoryRepository.save(notification);
		}
		return toDto(notification);
	}


	@Transactional
	public long markAllCurrentUserNotificationsAsRead() {
		User user = getAuthenticatedUser();
		notificationHistoryRepository.markAllAsRead(user.getId(), ZonedDateTime.now());
		return notificationHistoryRepository.countByUserIdAndReadAtIsNull(user.getId());
	}


	private User getAuthenticatedUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
			throw new AccessDeniedException("Пользователь не авторизован");
		}
		return userRepository.findByUsername(authentication.getName())
				.orElseThrow(() -> new AccessDeniedException("Пользователь не найден"));
	}


	private UserNotificationHistoryDto toDto(UserNotificationHistory notification) {
		return UserNotificationHistoryDto.builder()
				.id(notification.getId())
				.event(notification.getEvent())
				.title(notification.getTitle())
				.body(notification.getBody())
				.message(notification.getSourceMessage())
				.userId(notification.getUserId())
				.createdAt(notification.getCreatedAt())
				.readAt(notification.getReadAt())
				.read(notification.getReadAt() != null)
				.build();
	}


	private String buildNotificationBody(UserNotification notification) {
		String message = Objects.toString(notification.getMessage(), "").trim();
		return switch (notification.getEvent()) {
			case MENTIONED_USER -> appendDetails("Вас упомянули в чате", message);
			case MENTIONED_USER_IN_TASK_CHAT -> appendDetails("Вас упомянули в заявке", message);
			case NEW_TASK -> message.startsWith("Новая заявка")
					? message
					: appendDetails("Новая заявка назначена на Вас", message);
			case NEW_CHAT_MESSAGE -> appendDetails(
					"Новое сообщение в заявке, где Вы назначены исполнителем",
					message
			);
			case SLA_HALF_TIME_PASSED -> fallback(message, "SLA прошел больше чем на 50%");
			case SLA_OVERDUE -> fallback(message, "SLA нарушен");
			case CHAT_UNANSWERED_TOO_LONG -> fallback(message, "Есть чат без ответа");
			case DEADLINE_SOON -> fallback(message, "Скоро дедлайн по заявке");
			case DEADLINE_OVERDUE -> fallback(message, "Дедлайн нарушен");
			case INCIDENT_UPDATE_OVERDUE -> fallback(
					message,
					"Просрочено обязательное обновление по инциденту"
			);
			case INCIDENT_DETECTED -> fallback(message, "Обнаружен массовый сбой");
			case INCIDENT_RECOVERY_FAILED -> fallback(message, "Клиент сообщает, что после восстановления проблема сохраняется");
			case MONITORING_CONNECTION_LOST -> fallback(message, "Нет связи с Zabbix");
			case MONITORING_CONNECTION_RESTORED -> fallback(message, "Связь с Zabbix восстановлена");
		};
	}


	private String appendDetails(String prefix, String details) {
		return details == null || details.isBlank() ? prefix : prefix + ": " + details;
	}


	private String fallback(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}


	public boolean isNotificationEnabled(User user, UserNotificationEvent event) {
		if (user == null || event == null) {
			return false;
		}
		return switch (event) {
			case MENTIONED_USER -> Boolean.TRUE.equals(user.getNotifyChatPing());

			case MENTIONED_USER_IN_TASK_CHAT -> Boolean.TRUE.equals(user.getNotifyTaskChatPing());

			case NEW_TASK -> Boolean.TRUE.equals(user.getNotifyNewAssignedTask());

			case NEW_CHAT_MESSAGE -> Boolean.TRUE.equals(user.getNotifyTaskNewMessageAssigned());

			case SLA_HALF_TIME_PASSED -> Boolean.TRUE.equals(user.getNotifySlaHalfTimePassed());

			case SLA_OVERDUE -> Boolean.TRUE.equals(user.getNotifySlaOverdue());

			case CHAT_UNANSWERED_TOO_LONG -> Boolean.TRUE.equals(user.getNotifyChatUnansweredTooLong());

			case DEADLINE_SOON -> Boolean.TRUE.equals(user.getNotifyDeadlineOverdueBeforeMinutesEnabled());

			case DEADLINE_OVERDUE -> Boolean.TRUE.equals(user.getNotifyDeadlineOverdue());

			case INCIDENT_UPDATE_OVERDUE, INCIDENT_DETECTED, INCIDENT_RECOVERY_FAILED,
			     MONITORING_CONNECTION_LOST, MONITORING_CONNECTION_RESTORED -> true;
		};
	}


	@Transactional
	public void checkSlaNotifications() {
		ZonedDateTime now = ZonedDateTime.now();

		taskRepository.findAll().stream()
				.filter(task -> !Boolean.TRUE.equals(task.getCompleted()))
				.forEach(task -> {
					if (hasValidSla(task)) {
						checkSlaHalfTime(task, now);
						checkSlaOverdue(task, now);
					}

					checkDeadlineNotifications(task, now);
				});
	}


	@Transactional
	public void checkUnansweredChatNotifications() {
		checkUnansweredChatNotifications(ZonedDateTime.now());
	}


	void checkUnansweredChatNotifications(ZonedDateTime now) {
		List<User> notificationUsers = userRepository.findAll().stream()
				.filter(this::isAdminOrOperator)
				.filter(user -> user.getId() != null)
				.filter(user -> Boolean.TRUE.equals(user.getNotifyChatUnansweredTooLong()))
				.toList();
		if (notificationUsers.isEmpty()) {
			return;
		}
		for (Client client : clientRepository.findAll()) {
			ZonedDateTime firstUnansweredDate = getFirstUnansweredMessageDate(client);
			if (firstUnansweredDate == null) {
				continue;
			}
			// Ожидание считается один раз на чат: календарное или только рабочее время — по общей настройке
			long waitedSeconds = answerWaitingTimeService.waitedSeconds(firstUnansweredDate, now);
			for (User user : notificationUsers) {
				long minutes = getChatUnansweredTooLongMinutes(user);
				if (waitedSeconds < minutes * 60) {
					continue;
				}
				String key = "CHAT_UNANSWERED_TOO_LONG:%d:%d:%s".formatted(
						user.getId(),
						client.getId(),
						firstUnansweredDate.toInstant().toString()
				);
				if (!sentNotificationKeys.add(key)) {
					continue;
				}
				send(new UserNotification(
						UserNotificationEvent.CHAT_UNANSWERED_TOO_LONG,
						"Чат с клиентом «%s» без ответа дольше %d мин.".formatted(getClientDisplayName(client), minutes),
						user.getId()
				));
			}
		}
	}


	private void checkSlaHalfTime(Task task, ZonedDateTime now) {
		Sla sla = task.getSla();
		Duration duration = sla.getDuration();
		Duration pausedDuration = getPausedDuration(sla);
		Duration elapsed = Duration.between(sla.getStartDate(), now).minus(pausedDuration);
		if (elapsed.isNegative()) {
			return;
		}
		// Оранжевая зона начинается, когда остаётся orangeStartPercent% времени: прошло (100 - orangeStartPercent)%
		int elapsedPercent = 100 - appSettingsService.getSlaZones().orangeStartPercent();
		if (elapsed.toMillis() < Math.round(duration.toMillis() * elapsedPercent / 100.0)) {
			return;
		}
		String key = "SLA_HALF_TIME_PASSED:%d:%d:%s:%d".formatted(
				task.getId(),
				sla.getId(),
				sla.getStartDate().toInstant().toString(),
				duration.getSeconds()
		);
		if (!sentNotificationKeys.add(key)) {
			return;
		}
		notifyTaskResponsibleUsers(
				task,
				UserNotificationEvent.SLA_HALF_TIME_PASSED,
				"SLA по заявке «%s» прошел больше чем на %d%%".formatted(task.getName(), elapsedPercent)
		);
	}


	private void checkSlaOverdue(Task task, ZonedDateTime now) {
		Sla sla = task.getSla();
		if (sla == null || sla.getStartDate() == null || sla.getDuration() == null) {
			return;
		}
		Duration pausedDuration = getPausedDuration(sla);
		Duration elapsed = Duration.between(sla.getStartDate(), now).minus(pausedDuration);
		if (elapsed.isNegative()) {
			return;
		}
		if (elapsed.compareTo(sla.getDuration()) < 0) {
			return;
		}
		String key = "SLA_OVERDUE:%d:%d:%s:%d".formatted(
				task.getId(),
				sla.getId(),
				sla.getStartDate().toInstant().toString(),
				sla.getDuration().getSeconds()
		);
		if (!sentNotificationKeys.add(key)) {
			return;
		}
		notifyTaskResponsibleUsers(
				task,
				UserNotificationEvent.SLA_OVERDUE,
				"SLA нарушен по заявке «%s»".formatted(task.getName())
		);
	}


	private Duration getPausedDuration(Sla sla) {
		if (sla == null || sla.getId() == null) {
			return Duration.ZERO;
		}
		ZonedDateTime now = ZonedDateTime.now();
		long seconds = slaPauseRepository.findAllBySlaId(sla.getId()).stream()
				.filter(pause -> pause.getStartedAt() != null)
				.mapToLong(pause -> {
					ZonedDateTime end = pause.getEndedAt() == null
							? now
							: pause.getEndedAt();
					return Math.max(0, Duration.between(pause.getStartedAt(), end).getSeconds());
				})
				.sum();
		return Duration.ofSeconds(seconds);
	}


	private ZonedDateTime getFirstUnansweredMessageDate(Client client) {
		if (client == null || client.getMessages() == null) {
			return null;
		}
		List<Message> sortedMessages = client.getMessages().stream()
				.filter(message -> message.getDate() != null)
				.filter(message -> !Boolean.TRUE.equals(message.getDeleted()))
				.sorted()
				.toList();
		ZonedDateTime lastOperatorAnswerDate = sortedMessages.stream()
				.filter(this::isOutgoingOperatorMessage)
				.map(Message::getDate)
				.max(ZonedDateTime::compareTo)
				.orElse(null);
		List<Message> unansweredIncomingMessages = sortedMessages.stream()
				.filter(this::isIncomingMessage)
				.filter(message -> lastOperatorAnswerDate == null || message.getDate().isAfter(lastOperatorAnswerDate))
				.toList();
		if (unansweredIncomingMessages.isEmpty()) {
			return null;
		}
		Message lastMarkedMessage = unansweredIncomingMessages.stream()
				.filter(message ->
						message.getAnswerRequired() == AnswerRequired.ANSWER_REQUIRED ||
								message.getAnswerRequired() == AnswerRequired.ANSWER_NOT_REQUIRED
				)
				.reduce((first, second) -> second)
				.orElse(null);

		if (lastMarkedMessage == null || lastMarkedMessage.getAnswerRequired() != AnswerRequired.ANSWER_REQUIRED) {
			return null;
		}
		return lastMarkedMessage.getDate();
	}


	private boolean isIncomingMessage(Message message) {
		return Boolean.FALSE.equals(message.getIsSent()) && !Boolean.TRUE.equals(message.getIsComment());
	}


	private boolean isOutgoingOperatorMessage(Message message) {
		return Boolean.TRUE.equals(message.getIsSent()) && !Boolean.TRUE.equals(message.getIsComment());
	}


	private void notifyTaskResponsibleUsers(Task task, UserNotificationEvent event, String message) {
		if (task == null) {
			return;
		}
		User executor = task.getExecutor();
		if (executor != null && executor.getId() != null) {
			send(new UserNotification(
					event,
					message,
					executor.getId()
			));
			return;
		}
		notifyOperators(event, message);
	}


	private void notifyOperators(UserNotificationEvent event, String message) {
		userRepository.findAll().stream()
				.filter(this::isAdminOrOperator)
				.filter(user -> user.getId() != null)
				.forEach(user -> send(new UserNotification(
						event,
						message,
						user.getId()
				)));
	}


	private boolean isAdminOrOperator(User user) {
		if (user == null || user.getAuthorities() == null) {
			return false;
		}
		return user.getAuthorities().stream()
				.map(Object::toString)
				.anyMatch(authority ->
						authority.equals("ADMIN") ||
								authority.equals("MANAGER") ||
								authority.equals("OPERATOR") ||
								authority.equals("SENIOR_OPERATOR") ||
								authority.equals("ROLE_ADMIN") ||
								authority.equals("ROLE_MANAGER") ||
								authority.equals("ROLE_OPERATOR") ||
								authority.equals("ROLE_SENIOR_OPERATOR")
				);
	}


	private String getClientDisplayName(Client client) {
		if (client == null) {
			return "Без имени";
		}
		String lastname = Objects.toString(client.getLastname(), "").trim();
		String firstname = Objects.toString(client.getFirstname(), "").trim();
		String fullName = ("%s %s".formatted(lastname, firstname)).trim();
		if (!fullName.isBlank()) {
			return fullName;
		}
		if (client.getTelegramId() != null) {
			return "Telegram %d".formatted(client.getTelegramId());
		}
		return "Клиент #%d".formatted(client.getId());
	}


	private long getChatUnansweredTooLongMinutes(User user) {
		if (user == null || user.getNotifyChatUnansweredTooLongMinutes() == null) {
			return 30L;
		}
		int minutes = user.getNotifyChatUnansweredTooLongMinutes();
		if (minutes < 1) {
			return 30L;
		}
		return minutes;
	}


	private boolean hasValidSla(Task task) {
		if (task == null || task.getSla() == null) {
			return false;
		}
		Sla sla = task.getSla();
		return sla.getId() != null &&
				sla.getStartDate() != null &&
				sla.getDuration() != null &&
				!sla.getDuration().isZero() &&
				!sla.getDuration().isNegative();
	}


	private void checkDeadlineNotifications(Task task, ZonedDateTime now) {
		if (task == null || task.getId() == null || task.getDeadline() == null) {
			return;
		}
		Duration remaining = Duration.between(now, task.getDeadline());
		for (User user : getTaskNotificationUsers(task)) {
			if (user.getId() == null) {
				continue;
			}
			boolean deadlineSoonEnabled = Boolean.TRUE.equals(user.getNotifyDeadlineOverdueBeforeMinutesEnabled());
			boolean deadlineOverdueEnabled = Boolean.TRUE.equals(user.getNotifyDeadlineOverdue());
			if (!deadlineSoonEnabled && !deadlineOverdueEnabled) {
				continue;
			}
			if (!remaining.isNegative() && !remaining.isZero()) {
				if (deadlineSoonEnabled) {
					long beforeMinutes = getDeadlineOverdueBeforeMinutes(user);
					checkDeadlineSoon(task, user, remaining, beforeMinutes);
				}
				continue;
			}
			if (deadlineOverdueEnabled) {
				checkDeadlineOverdue(task, user);
			}
		}
	}


	private void checkDeadlineSoon(Task task, User user, Duration remaining, long beforeMinutes) {
		if (remaining.compareTo(Duration.ofMinutes(beforeMinutes)) > 0) {
			return;
		}
		String key = "DEADLINE_SOON:%d:%d:%s:%d".formatted(
				user.getId(),
				task.getId(),
				task.getDeadline().toInstant().toString(),
				beforeMinutes
		);
		if (!sentNotificationKeys.add(key)) {
			return;
		}
		send(new UserNotification(
				UserNotificationEvent.DEADLINE_SOON,
				"До дедлайна по заявке «%s» осталось меньше %d мин.".formatted(task.getName(), beforeMinutes),
				user.getId()
		));
	}


	private void checkDeadlineOverdue(Task task, User user) {
		String key = "DEADLINE_OVERDUE:%d:%d:%s".formatted(
				user.getId(),
				task.getId(),
				task.getDeadline().toInstant().toString()
		);
		if (!sentNotificationKeys.add(key)) {
			return;
		}
		send(new UserNotification(
				UserNotificationEvent.DEADLINE_OVERDUE,
				"Дедлайн нарушен по заявке «%s»".formatted(task.getName()),
				user.getId()
		));
	}


	private List<User> getTaskNotificationUsers(Task task) {
		if (task == null) {
			return List.of();
		}
		User executor = task.getExecutor();
		if (executor != null && executor.getId() != null) {
			return List.of(executor);
		}
		return userRepository.findAll().stream()
				.filter(this::isAdminOrOperator)
				.filter(user -> user.getId() != null)
				.toList();
	}


	private long getDeadlineOverdueBeforeMinutes(User user) {
		if (user == null || user.getNotifyDeadlineOverdueBeforeMinutes() == null) {
			return 30L;
		}
		int minutes = user.getNotifyDeadlineOverdueBeforeMinutes();
		if (minutes < 1) {
			return 30L;
		}
		return minutes;
	}

}