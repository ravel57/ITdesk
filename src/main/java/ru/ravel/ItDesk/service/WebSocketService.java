package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.ClientMessage;
import ru.ravel.ItDesk.dto.ForceLogoutDto;
import ru.ravel.ItDesk.dto.TaskMessageDto;
import ru.ravel.ItDesk.dto.TaskUpdatedDto;
import ru.ravel.ItDesk.dto.UserNotificationHistoryDto;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.GlobalNotification;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class WebSocketService {

	private final SimpMessagingTemplate simpMessaging;
	private final ClientPortalSocketService clientPortalSocketService;
	private final UserService userService;
	private final ClientRepository clientRepository;
	private final TaskRepository taskRepository;


	/**
	 * Compatibility entry point. Client collections are always filtered and sent
	 * through per-user destinations; never broadcast tenant data globally.
	 */
	public void sendClients(List<Client> clients) {
		for (User user : userService.getUsers()) {
			if (!isStaff(user)) {
				continue;
			}
			simpMessaging.convertAndSendToUser(
					user.getUsername(),
					"/topic/clients/",
					userService.filterClientsByUser(clients, user)
			);
		}
	}


	public void getAuthenticatedUsers(Set<User> users) {
		simpMessaging.convertAndSend("/topic/authenticated-users/", users);
	}


	public void sendNewMessages(ClientMessage clientMessage) {
		if (clientMessage == null || clientMessage.getClient() == null || clientMessage.getMessage() == null) {
			return;
		}
		sendClientEvent("/topic/client-messages/", clientMessage.getClient(), clientMessage);
		clientPortalSocketService.sendMessage(clientMessage.getClient(), clientMessage.getMessage());
	}


	public void editedMessage(ClientMessage clientMessage) {
		if (clientMessage == null || clientMessage.getClient() == null || clientMessage.getMessage() == null) {
			return;
		}
		sendClientEvent("/topic/client-message-edited/", clientMessage.getClient(), clientMessage);
		clientPortalSocketService.sendMessage(clientMessage.getClient(), clientMessage.getMessage());
	}


	public void deletedMessage(ClientMessage clientMessage) {
		if (clientMessage == null || clientMessage.getClient() == null || clientMessage.getMessage() == null) {
			return;
		}
		sendClientEvent("/topic/client-message-deleted/", clientMessage.getClient(), clientMessage);
		clientPortalSocketService.sendMessageDeleted(
				clientMessage.getClient(),
				clientMessage.getMessage().getId()
		);
	}


	public void markRead(Client client) {
		if (client == null) {
			return;
		}
		sendClientEvent("/topic/mark-read/", client, client);
	}


	public void supportMessages(List<Message> supportMessages) {
		simpMessaging.convertAndSend("/topic/support-messages/", supportMessages);
	}


	public void globalNotification(GlobalNotification globalNotification) {
		simpMessaging.convertAndSend("/topic/global-notifications/", globalNotification);
	}


	public void userNotification(String username, UserNotificationHistoryDto userNotification) {
		if (username == null || username.isBlank() || userNotification == null) {
			return;
		}
		simpMessaging.convertAndSendToUser(username, "/topic/user-notification/", userNotification);
	}


	public void taskMessage(Long clientId, Long taskId, Message message) {
		if (taskId == null || message == null) {
			return;
		}
		Task task = taskRepository.findById(taskId).orElse(null);
		Client client = resolveTaskClient(clientId, taskId);
		if (task == null || client == null) {
			return;
		}
		TaskMessageDto payload = new TaskMessageDto(client.getId(), taskId, message);
		// Внутренний чат заявки менеджеру организации (OBSERVER) не отправляется.
		sendTaskEvent("/topic/task-messages/", client, task,
				user -> userService.canUserSeeTaskChat(user) ? payload : null);
	}


	/**
	 * Инцидент изменился: фронт перезагружает список/карточку через API (там же проверка доступа).
	 */
	public void incidentUpdated(String username, Long incidentId) {
		if (username == null || username.isBlank() || incidentId == null) {
			return;
		}
		simpMessaging.convertAndSendToUser(username, "/topic/incidents-updated/", Map.of("incidentId", incidentId));
	}


	public void taskUpdated(TaskUpdatedDto taskUpdatedDto) {
		if (taskUpdatedDto == null || taskUpdatedDto.getTask() == null) {
			return;
		}
		Long taskId = asLong(taskUpdatedDto.getTask().get("id"));
		if (taskId == null) {
			return;
		}
		Task task = taskRepository.findById(taskId).orElse(null);
		Client client = resolveTaskClient(taskUpdatedDto.getClientId(), taskId);
		if (task == null || client == null) {
			return;
		}
		TaskUpdatedDto safeDto = new TaskUpdatedDto(client.getId(), taskUpdatedDto.getTask());
		TaskUpdatedDto withoutChatDto = safeDto.withoutTaskMessages();
		sendTaskEvent("/topic/task-updated/", client, task,
				user -> userService.canUserSeeTaskChat(user) ? safeDto : withoutChatDto);
	}


	/**
	 * Инцидент изменился: фронт перезагружает список/карточку через API (там же проверка доступа).
	 */
	public void incidentUpdated(String username, Long incidentId) {
		if (username == null || username.isBlank() || incidentId == null) {
			return;
		}
		simpMessaging.convertAndSendToUser(username, "/topic/incidents-updated/", Map.of("incidentId", incidentId));
	}


	public void forceLogout(String username, String newSessionMarker) {
		if (username == null || username.isBlank()) {
			return;
		}
		simpMessaging.convertAndSendToUser(
				username,
				"/topic/force-logout/",
				new ForceLogoutDto(username, newSessionMarker)
		);
	}


	public void clientsUpdated() {
		simpMessaging.convertAndSend("/topic/clients-updated/", true);
	}


	public void sendClientsToUser(String username, List<Client> clients) {
		if (username == null || username.isBlank()) {
			return;
		}
		User targetUser = userService.getUserByUsername(username);
		if (!isStaff(targetUser)) {
			throw new AccessDeniedException("Client list WebSocket destination is staff-only");
		}
		simpMessaging.convertAndSendToUser(
				username,
				"/topic/clients/",
				userService.filterClientsByUser(clients, targetUser)
		);
	}


	private boolean isStaff(User user) {
		return userService.isAdmin(user)
				|| userService.isManager(user)
				|| userService.isOperator(user)
				|| userService.isObserver(user);
	}


	private void sendClientEvent(String destination, Client client, Object payload) {
		for (User user : userService.getUsers()) {
			if (!(userService.isAdmin(user) || userService.isManager(user) || userService.isOperator(user))) {
				continue;
			}
			try {
				userService.assertUserCanAccessClient(user, client);
				simpMessaging.convertAndSendToUser(user.getUsername(), destination, payload);
			} catch (AccessDeniedException ignored) {
				// Access filtering is intentionally fail-closed for realtime updates.
			}
		}
	}


	/** payloadForUser возвращает null, если пользователю событие не отправляется. */
	private void sendTaskEvent(String destination, Client client, Task task, Function<User, Object> payloadForUser) {
		for (User user : userService.getUsers()) {
			if (!userService.canUserAccessTask(user, client, task)) {
				continue;
			}
			Object payload = payloadForUser.apply(user);
			if (payload != null) {
				simpMessaging.convertAndSendToUser(user.getUsername(), destination, payload);
			}
		}
	}


	private Client resolveTaskClient(Long clientId, Long taskId) {
		if (clientId != null) {
			Client client = clientRepository.findById(clientId).orElse(null);
			if (client != null && client.getTasks() != null &&
					client.getTasks().stream().anyMatch(item -> item != null && taskId.equals(item.getId()))) {
				return client;
			}
		}
		return clientRepository.findByTaskId(taskId).orElse(null);
	}


	private Long asLong(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof String string) {
			try {
				return Long.parseLong(string);
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}
}
