package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.automatosation.*;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;

import java.time.Instant;


@Service
public class EventPublisher {

	private final AutomationOutboxRepository outboxRepository;
	private final ObjectMapper objectMapper;

	public EventPublisher(
			AutomationOutboxRepository outboxRepository,
			@Qualifier("legacyObjectMapper") ObjectMapper objectMapper
	) {
		this.outboxRepository = outboxRepository;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public void publish(TriggerType triggerType, Object payloadObj) {
		JsonNode payload = objectMapper.valueToTree(payloadObj);
		Event event = new Event();
		event.setTriggerType(triggerType);
		event.setPayload(payload);
		event.setStatus(EventStatus.NEW);
		event.setRetries(0);
		event.setAvailableAt(Instant.now());
		event.setLastError(null);
		outboxRepository.save(event);
	}


	@Transactional
	public void publishWithDelay(TriggerType triggerType, Object payloadObj, Instant availableAt) {
		JsonNode payload = objectMapper.valueToTree(payloadObj);
		Event e = new Event();
		e.setTriggerType(triggerType);
		e.setPayload(payload);
		e.setStatus(EventStatus.NEW);
		e.setRetries(0);
		e.setAvailableAt(availableAt);
		e.setLastError(null);
		outboxRepository.save(e);
	}

}