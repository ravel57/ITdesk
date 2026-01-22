package ru.ravel.ItDesk.component;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.automatosation.AutomationOutboxEvent;
import ru.ravel.ItDesk.model.automatosation.OutboxStatus;
import ru.ravel.ItDesk.repository.AutomationOutboxDao;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;
import ru.ravel.ItDesk.service.AutomationEngine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class AutomationWorker {

	private final AutomationOutboxDao outboxDao;
	private final AutomationOutboxRepository repo;
	private final AutomationEngine automationEngine; // твой движок условий/действий


	@Scheduled(fixedDelayString = "${automation.worker.delay-ms:200}")
	public void poll() {
		List<UUID> ids = outboxDao.fetchAndMarkProcessing(50);
		for (UUID id : ids) {
			processOne(id);
		}
	}


	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void processOne(UUID id) {
		AutomationOutboxEvent e = repo.findById(id).orElseThrow();
		try {
			automationEngine.handleEvent(e); // проверка правил и выполнение действий
			e.setStatus(OutboxStatus.DONE);
			e.setLastError(null);
			repo.save(e);
		} catch (Exception ex) {
			int retries = e.getRetries();
			long backoffSec = Math.min(60, 1L << Math.min(retries, 10)); // 1,2,4...<=60
			e.setRetries(retries + 1);
			e.setStatus(OutboxStatus.NEW);
			e.setAvailableAt(Instant.now().plusSeconds(backoffSec));
			e.setLastError(ex.getMessage());
			repo.save(e);
		}
	}
}