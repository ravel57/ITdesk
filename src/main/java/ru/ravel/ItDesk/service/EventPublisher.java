package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.ActorType;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.automatosation.EventStatus;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.time.Instant;


@Service
public class EventPublisher {

	private final AutomationOutboxRepository outboxRepository;
	private final ObjectMapper objectMapper;
	private final UserRepository userRepository;

	public EventPublisher(
			AutomationOutboxRepository outboxRepository,
			@Qualifier("legacyObjectMapper") ObjectMapper objectMapper,
			UserRepository userRepository
	) {
		this.outboxRepository = outboxRepository;
		this.objectMapper = objectMapper;
		this.userRepository = userRepository;
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
		enrichActor(event);
		outboxRepository.save(event);
	}


	@Transactional
	public void publishWithDelay(TriggerType triggerType, Object payloadObj, Instant availableAt) {
		JsonNode payload = objectMapper.valueToTree(payloadObj);
		Event event = new Event();
		event.setTriggerType(triggerType);
		event.setPayload(payload);
		event.setStatus(EventStatus.NEW);
		event.setRetries(0);
		event.setAvailableAt(availableAt);
		event.setLastError(null);
		enrichActor(event);
		outboxRepository.save(event);
	}


	private void enrichActor(Event event) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			setSystemActor(event);
			return;
		}
		Object principal = authentication.getPrincipal();
		if (principal instanceof User user) {
			setUserActor(event, user);
			return;
		}
		String username = authentication.getName();
		if (username == null || username.isBlank() || "anonymousUser".equals(username)) {
			setSystemActor(event);
			return;
		}
		userRepository.findByUsername(username)
				.ifPresentOrElse(
						user -> setUserActor(event, user),
						() -> {
							event.setActorType(ActorType.USER);
							event.setActorUsername(username);
							event.setActorDisplayName(username);
						}
				);
	}


	private void setUserActor(Event event, User user) {
		event.setActorType(ActorType.USER);
		event.setActorUserId(user.getId());
		event.setActorUsername(user.getUsername());
		event.setActorDisplayName(getUserDisplayName(user));
	}


	private void setSystemActor(Event event) {
		event.setActorType(ActorType.SYSTEM);
		event.setActorDisplayName("Система");
	}


	private String getUserDisplayName(User user) {
		String lastname = user.getLastname() == null ? "" : user.getLastname().trim();
		String firstname = user.getFirstname() == null ? "" : user.getFirstname().trim();
		String fullName = (lastname + " " + firstname).trim();
		if (!fullName.isBlank()) {
			return fullName;
		}
		if (!user.getUsername().isBlank()) {
			return user.getUsername();
		}
		return "Пользователь " + user.getId();
	}
}