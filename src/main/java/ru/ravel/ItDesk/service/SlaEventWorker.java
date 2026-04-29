package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Sla;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.Duration;
import java.util.Map;


@Service
@RequiredArgsConstructor
public class SlaEventWorker {

	private final TaskRepository taskRepository;
	private final SlaService slaService;
	private final EventPublisher eventPublisher;
	private final AutomationOutboxRepository outboxRepository;

	@Scheduled(fixedDelayString = "${sla.worker.delay-ms:60000}")
	public void checkSla() {
		var tasks = taskRepository.findAllActiveWithSla();
		for (Task task : tasks) {
			Sla sla = task.getSla();
			if (sla == null) {
				continue;
			}
			Duration remaining = slaService.remaining(sla);
			if (remaining.isNegative() || remaining.isZero()) {
				publishOnce(task, sla, TriggerType.SLA_BREACHED, remaining);
			} else if (remaining.toMinutes() <= 30) {
				publishOnce(task, sla, TriggerType.SLA_WARNING, remaining);
			}
		}
	}

	private void publishOnce(Task task, Sla sla, TriggerType triggerType, Duration remaining) {
		boolean alreadyPublished = outboxRepository.existsByTriggerTypeAndTaskId(
				triggerType.name(),
				task.getId()
		);

		if (alreadyPublished) {
			return;
		}

		eventPublisher.publish(triggerType, Map.of(
				"task", task,
				"sla", sla,
				"remainingSeconds", remaining.getSeconds()
		));
	}
}