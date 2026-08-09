package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.GlobalSearchResultDto;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class GlobalSearchAccessService {

	private final UserService userService;
	private final ClientRepository clientRepository;
	private final TaskRepository taskRepository;


	public List<GlobalSearchResultDto> filterForCurrentUser(List<GlobalSearchResultDto> results) {
		if (results == null || results.isEmpty()) {
			return Collections.emptyList();
		}
		User currentUser = userService.getCurrentUser();
		return results.stream()
				.filter(result -> canAccess(currentUser, result))
				.toList();
	}


	private boolean canAccess(User user, GlobalSearchResultDto result) {
		if (user == null || result == null || result.getEntityType() == null) {
			return false;
		}
		String type = result.getEntityType().trim().toUpperCase(Locale.ROOT);
		return switch (type) {
			case "CLIENT", "CLIENT_MESSAGE" -> canAccessClient(user, clientId(result));
			case "TASK", "TASK_MESSAGE" -> canAccessTask(user, taskId(result));
			// These entity types are reference data already available to the
			// same staff roles through their normal APIs.
			case "USER", "SERVICE", "KNOWLEDGE" -> true;
			default -> false;
		};
	}


	private Long clientId(GlobalSearchResultDto result) {
		if (result.getClientId() != null) {
			return result.getClientId();
		}
		return "CLIENT".equalsIgnoreCase(result.getEntityType()) ? result.getEntityId() : null;
	}


	private Long taskId(GlobalSearchResultDto result) {
		if (result.getTaskId() != null) {
			return result.getTaskId();
		}
		return "TASK".equalsIgnoreCase(result.getEntityType()) ? result.getEntityId() : null;
	}


	private boolean canAccessClient(User user, Long clientId) {
		if (clientId == null) {
			return false;
		}
		Client client = clientRepository.findById(clientId).orElse(null);
		if (client == null) {
			return false;
		}
		try {
			userService.assertUserCanAccessClient(user, client);
			return true;
		} catch (AccessDeniedException ignored) {
			return false;
		}
	}


	private boolean canAccessTask(User user, Long taskId) {
		if (taskId == null) {
			return false;
		}
		Task task = taskRepository.findById(taskId).orElse(null);
		if (task == null) {
			return false;
		}
		Client client = clientRepository.findByTaskId(taskId).orElse(null);
		return client != null && userService.canUserAccessTask(user, client, task);
	}
}
