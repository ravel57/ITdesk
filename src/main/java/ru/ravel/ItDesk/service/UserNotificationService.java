package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AnswerRequired;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.SlaPauseRepository;
import ru.ravel.ItDesk.repository.TaskRepository;
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

	@Value("${app.notifications.unanswered-minutes:30}")
	private long unansweredNotificationMinutes;

	private final Set<String> sentNotificationKeys = ConcurrentHashMap.newKeySet();


	public void send(UserNotification notification) {
		if (notification == null || notification.getUserId() == null || notification.getEvent() == null) {
			return;
		}
		userRepository.findById(notification.getUserId())
				.filter(user -> isNotificationEnabled(user, notification.getEvent()))
				.ifPresent(user -> webSocketService.userNotification(notification));
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
		};
	}


	@Transactional(readOnly = true)
	public void checkSlaNotifications() {
		ZonedDateTime now = ZonedDateTime.now();
		taskRepository.findAll().stream()
				.filter(task -> !Boolean.TRUE.equals(task.getCompleted()))
				.filter(task -> task.getSla() != null)
				.filter(task -> task.getSla().getId() != null)
				.filter(task -> task.getSla().getStartDate() != null)
				.filter(task -> task.getSla().getDuration() != null)
				.filter(task -> !task.getSla().getDuration().isZero())
				.filter(task -> !task.getSla().getDuration().isNegative())
				.forEach(task -> {
					checkSlaHalfTime(task, now);
					checkSlaOverdue(task);
				});
	}


	@Transactional(readOnly = true)
	public void checkUnansweredChatNotifications() {
		ZonedDateTime now = ZonedDateTime.now();

		for (Client client : clientRepository.findAll()) {
			ZonedDateTime firstUnansweredDate = getFirstUnansweredMessageDate(client);

			if (firstUnansweredDate == null) {
				continue;
			}

			if (firstUnansweredDate.plusMinutes(unansweredNotificationMinutes).isAfter(now)) {
				continue;
			}

			String key = "CHAT_UNANSWERED_TOO_LONG:%d:%s".formatted(
					client.getId(),
					firstUnansweredDate.toInstant().toString()
			);

			if (!sentNotificationKeys.add(key)) {
				continue;
			}

			notifyOperators(
					UserNotificationEvent.CHAT_UNANSWERED_TOO_LONG,
					"Чат с клиентом «%s» без ответа дольше %d мин.".formatted(
							getClientDisplayName(client),
							unansweredNotificationMinutes
					)
			);
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

		if (elapsed.compareTo(duration.dividedBy(2)) < 0) {
			return;
		}

		String key = "SLA_HALF_TIME_PASSED:%d:%d".formatted(task.getId(), sla.getId());

		if (!sentNotificationKeys.add(key)) {
			return;
		}

		notifyTaskResponsibleUsers(
				task,
				UserNotificationEvent.SLA_HALF_TIME_PASSED,
				"SLA по заявке «%s» прошел больше чем на 50%%".formatted(task.getName())
		);
	}


	private void checkSlaOverdue(Task task) {
		Sla sla = task.getSla();
		Duration remaining = getRemaining(sla);

		if (remaining.getSeconds() > 0) {
			return;
		}

		String key = "SLA_OVERDUE:%d:%d".formatted(task.getId(), sla.getId());

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


	private ZonedDateTime getDeadline(Sla sla) {
		if (sla == null || sla.getStartDate() == null || sla.getDuration() == null) {
			return null;
		}

		return sla.getStartDate()
				.plus(sla.getDuration())
				.plus(getPausedDuration(sla));
	}


	private Duration getRemaining(Sla sla) {
		ZonedDateTime deadline = getDeadline(sla);

		if (deadline == null) {
			return Duration.ZERO;
		}

		return Duration.between(ZonedDateTime.now(), deadline);
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

		return unansweredIncomingMessages.getFirst().getDate();
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
								authority.equals("OPERATOR") ||
								authority.equals("ROLE_ADMIN") ||
								authority.equals("ROLE_OPERATOR")
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
}