package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.automatosation.AutomationRuleStatus;
import ru.ravel.ItDesk.model.automatosation.EventStatus;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;
import ru.ravel.ItDesk.repository.AutomationTriggerRepository;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;

import java.time.Instant;


@Service
@RequiredArgsConstructor
public class AutomationItemProcessor {

	private final AutomationOutboxRepository outboxRepository;
	private final AutomationScriptRuntime scriptRuntime;
	private final AutomationTriggerRepository automationTriggerRepository;
	private final ClientRepository clientRepository;

	private final ObjectMapper objectMapper;

	private final MessageRepository messageRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void processOneTx(Long id) {
		Event event = outboxRepository.findById(id).orElseThrow();
		try {
			handleEvent(event); // НЕ глотаем ошибки
			event.setStatus(EventStatus.DONE);
			event.setLastError(null);
			outboxRepository.save(event);
		} catch (Exception ex) {
			int retries = event.getRetries();
			long backoffSec = Math.min(60, 1L << Math.min(retries, 10));
			event.setRetries(retries + 1);
			event.setStatus(EventStatus.NEW);
			event.setAvailableAt(Instant.now().plusSeconds(backoffSec));
			event.setLastError(ex.getMessage());
			outboxRepository.save(event);
			throw ex;
		}
	}


	public void handleEvent(Event event) {
		if (event == null || event.getPayload() == null) {
			return;
		}
		JsonNode client = event.getPayload().get("client");
		if (client != null) {
			Client foundClient = clientRepository.findById(client.get("id").asLong()).orElseThrow();
			JsonNode messagesNode = objectMapper.valueToTree(foundClient.getMessages());
			event.getPayload().withObject("client").set("messages", messagesNode);
			JsonNode incomeMessagesNode = objectMapper.valueToTree(foundClient.getMessages().stream().filter(m -> !m.isSent()).toList());
			event.getPayload().withObject("client").set("incomeMessages", incomeMessagesNode);
			JsonNode outcomeMessagesNode = objectMapper.valueToTree(foundClient.getMessages().stream().filter(Message::isSent).toList());
			event.getPayload().withObject("client").set("outcomeMessages", outcomeMessagesNode);
			JsonNode tasksNode = objectMapper.valueToTree(foundClient.getTasks());
			event.getPayload().withObject("client").set("tasks", tasksNode);
			JsonNode openTasksNode = objectMapper.valueToTree(foundClient.getTasks().stream().filter(task -> !task.getCompleted()).toList());
			event.getPayload().withObject("client").set("openTasks", openTasksNode);
		}
		automationTriggerRepository
				.findEnabledByTriggerTypeOrdered(event.getTriggerType(), AutomationRuleStatus.ENABLED)
				.forEach(trigger -> {
					try {
						boolean ok = scriptRuntime.evaluateExpression(trigger.getExpression(), event.getPayload());
						if (ok) {
							scriptRuntime.executeActions(
									trigger.getAction(),
									new AutomationExecutionContext(trigger, event)
							);
						}
					} catch (Exception ignored) {
						// можно писать в trigger_run_log, но не валить весь event
					}
				});
	}

//	/**
//	 * Добавляем в payload вычисленные поля, которые нужны выражениям.
//	 * Вместо client.messages.size() -> client.messagesCount
//	 */
//	private void enrichPayload(Event event) {
//		JsonNode payload = event.getPayload();
//
//		JsonNode clientNode = payload.get("client");
//		if (clientNode == null || clientNode.isNull()) return;
//
//		JsonNode idNode = clientNode.get("id");
//		if (idNode == null || idNode.isNull()) return;
//
//		long clientId = idNode.asLong();
//		if (clientId <= 0) return;
//
//		long messagesCount = messageRepository.countByClientId(clientId);
//
//		// client.messagesCount = N
//		payload.withObject("client").put("messagesCount", messagesCount);
//	}
}