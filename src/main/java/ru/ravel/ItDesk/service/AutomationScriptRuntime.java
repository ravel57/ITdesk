package ru.ravel.ItDesk.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.automatosation.AutomationOutboxEvent;

@Slf4j
@Service
public class DefaultAutomationActionExecutor {

	@Override
	public void execute(AutomationOutboxEvent event) {
		log.info("Automation action: type={}, payload={}", event.getTriggerType(), event.getPayload());
	}
}
