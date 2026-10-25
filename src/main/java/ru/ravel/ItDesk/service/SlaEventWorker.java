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
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


@Service
@RequiredArgsConstructor
public class SlaEventWorker {

	private final TaskRepository taskRepository;
	private final SlaService slaService;
	private final WorkingTimeService workingTimeService;
	private final EventPublisher eventPublisher;
	private final AutomationOutboxRepository outboxRepository;
	private final AppSettingsService appSettingsService;

	@Scheduled(fixedDelayString = "${app.sla.worker.delay-ms:60000}")
	public void checkSla() {
		WorkingTimeService.WorkingTimeState workingTimeState = workingTimeService.getCurrentState();
		// Оранжевая зона (предупреждение) начинается по общей настройке, а не жёстко на половине срока
		double warningFraction = appSettingsService.getSlaZones().orangeStartFraction();
		List<Task> tasks = taskRepository.findAllActiveWithSla();
		for (Task task : tasks) {
			Sla sla = task.getSla();
			if (sla == null) {
				continue;
			}
			if (!workingTimeState.enabled()) {
				slaService.resumeNonWorkingTimePause(sla);
			} else if (!workingTimeState.workingNow()) {
				slaService.pauseForNonWorkingTime(sla);
				continue;
			} else {
				slaService.resumeNonWorkingTimePause(sla);
			}
			if (slaService.isPaused(sla)) {
				continue;
			}
			Duration remaining = slaService.remaining(sla);
			if (remaining.isNegative() || remaining.isZero()) {
				publishOnce(task, sla, TriggerType.SLA_BREACHED, remaining);
			} else if (isInWarningZone(remaining, sla.getDuration(), warningFraction)) {
				publishOnce(task, sla, TriggerType.SLA_WARNING, remaining);
			}
		}
		checkFirstResponseSla(warningFraction);
	}


	private static boolean isInWarningZone(Duration remaining, Duration total, double warningFraction) {
		long warningMillis = Math.round(total.toMillis() * warningFraction);
		return remaining.toMillis() <= warningMillis;
	}

	private void checkFirstResponseSla(double warningFraction) {
		// The deadline is already calculated by the selected service calendar.
		// Do not globally suppress checks outside the common work schedule: a
		// service may use a 24/7 policy.
		for (Task task : taskRepository.findAllActiveAwaitingFirstResponse()) {
			if (task == null || Boolean.TRUE.equals(task.getFrozen()) || task.getFirstResponseDeadline() == null) {
				continue;
			}
			Duration remaining = Duration.between(ZonedDateTime.now(), task.getFirstResponseDeadline());
			long totalSeconds = Math.max(1L, Math.multiplyExact(
					Objects.requireNonNullElse(task.getFirstResponseSlaMinutes(), 1).longValue(),
					60L
			));
			if (remaining.isNegative() || remaining.isZero()) {
				publishFirstResponseOnce(task, TriggerType.FIRST_RESPONSE_SLA_BREACHED, remaining);
			} else if (isInWarningZone(remaining, Duration.ofSeconds(totalSeconds), warningFraction)) {
				publishFirstResponseOnce(task, TriggerType.FIRST_RESPONSE_SLA_WARNING, remaining);
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
				"task", taskRef(task),
				"sla", slaSnapshot(sla),
				"remainingSeconds", remaining.getSeconds()
		));
	}

	private void publishFirstResponseOnce(Task task, TriggerType triggerType, Duration remaining) {
		if (outboxRepository.existsByTriggerTypeAndTaskId(triggerType.name(), task.getId())) {
			return;
		}
		eventPublisher.publish(triggerType, Map.of(
				"task", taskRef(task),
				"firstResponseDeadline", task.getFirstResponseDeadline(),
				"remainingSeconds", remaining.getSeconds()
		));
	}


	private Map<String, Object> taskRef(Task task) {
		return Map.of("id", task.getId());
	}


	private Map<String, Object> slaSnapshot(Sla sla) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("id", sla.getId());
		snapshot.put("startDate", sla.getStartDate());
		snapshot.put("duration", sla.getDuration());
		return snapshot;
	}
}