package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;


@Service
public class AutomationPayloadService {

	private final ClientRepository clientRepository;
	private final TaskRepository taskRepository;
	private final ObjectMapper objectMapper;


	public AutomationPayloadService(
			ClientRepository clientRepository,
			TaskRepository taskRepository,
			@Qualifier("legacyObjectMapper") ObjectMapper objectMapper
	) {
		this.clientRepository = clientRepository;
		this.taskRepository = taskRepository;
		this.objectMapper = objectMapper;
	}


	@Transactional(readOnly = true)
	public void enrich(Event event) {
		if (event == null || event.getPayload() == null || !event.getPayload().isObject()) {
			return;
		}

		ObjectNode payload = (ObjectNode) event.getPayload();
		Long taskId = readId(payload.path("task"));
		Long clientId = readId(payload.path("client"));

		if (taskId != null) {
			Optional<Task> task = taskRepository.findById(taskId);
			if (task.isPresent()) {
				ObjectNode serializedTask = objectMapper.valueToTree(task.get());
				objectNode(payload, "task").setAll(serializedTask);
				if (clientId == null) {
					clientId = clientRepository.findByTaskId(taskId).map(Client::getId).orElse(null);
				}
			} else {
				objectNode(payload, "task").put("deleted", true);
			}
		}

		if (clientId == null) {
			return;
		}

		Optional<Client> foundClientOpt = clientRepository.findById(clientId);
		if (foundClientOpt.isEmpty()) {
			objectNode(payload, "client").put("deleted", true);
			return;
		}

		Client client = foundClientOpt.get();
		ObjectNode serializedClient = objectMapper.valueToTree(client);
		objectNode(payload, "client").setAll(serializedClient);

		List<Message> messages = client.getMessages().stream()
				.sorted(Comparator.comparing(
						Message::getDate,
						Comparator.nullsLast(Comparator.naturalOrder())
				))
				.toList();
		objectNode(payload, "client").set("messages", objectMapper.valueToTree(messages));
		objectNode(payload, "client").set(
				"incomeMessages",
				objectMapper.valueToTree(messages.stream().filter(message -> !Boolean.TRUE.equals(message.getIsSent())).toList())
		);
		objectNode(payload, "client").set(
				"outcomeMessages",
				objectMapper.valueToTree(messages.stream().filter(message -> Boolean.TRUE.equals(message.getIsSent())).toList())
		);

		List<Task> tasks = client.getTasks().stream()
				.sorted(Comparator.comparing(Task::getId, Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();
		objectNode(payload, "client").set("tasks", objectMapper.valueToTree(tasks));
		objectNode(payload, "client").set(
				"openTasks",
				objectMapper.valueToTree(tasks.stream().filter(task -> !Boolean.TRUE.equals(task.getCompleted())).toList())
		);
	}


	private ObjectNode objectNode(ObjectNode root, String field) {
		JsonNode current = root.get(field);
		if (current instanceof ObjectNode objectNode) {
			return objectNode;
		}
		ObjectNode created = objectMapper.createObjectNode();
		root.set(field, created);
		return created;
	}



	private Long readId(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		JsonNode id = node.get("id");
		return id == null || id.isNull() || !id.canConvertToLong() ? null : id.asLong();
	}
}
