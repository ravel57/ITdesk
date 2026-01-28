package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.Sla;
import ru.ravel.ItDesk.model.SlaPause;
import ru.ravel.ItDesk.repository.SlaRepository;

import java.time.Duration;
import java.time.ZonedDateTime;


@Service
@RequiredArgsConstructor
public class SlaService {

	private final SlaRepository slaRepository;


	@Transactional
	public void pause(Sla sla, String reason) {
		if (sla == null) {
			return;
		}
		boolean alreadyPaused = sla.getPauses().stream().anyMatch(p -> p.getEndedAt() == null);
		if (alreadyPaused) {
			return;
		}
		SlaPause pause = new SlaPause();
		pause.setSla(sla);
		pause.setStartedAt(ZonedDateTime.now());
		pause.setEndedAt(null);
		pause.setReason(reason);
		sla.getPauses().add(pause);
		slaRepository.save(sla);
	}

	@Transactional
	public void resume(Sla sla) {
		if (sla == null) {
			return;
		}
		SlaPause active = sla.getPauses().stream()
				.filter(p -> p.getEndedAt() == null)
				.findFirst()
				.orElse(null);
		if (active == null) {
			return;
		}
		active.setEndedAt(ZonedDateTime.now());
		slaRepository.save(sla);
	}

	@Transactional(readOnly = true)
	public boolean isPaused(Sla sla) {
		if (sla == null) {
			return false;
		}
		return sla.getPauses().stream().anyMatch(p -> p.getEndedAt() == null);
	}

	@Transactional(readOnly = true)
	public Duration getPausedDuration(Sla sla) {
		if (sla == null) {
			return Duration.ZERO;
		}
		ZonedDateTime now = ZonedDateTime.now();
		long seconds = sla.getPauses().stream().mapToLong(p -> {
			ZonedDateTime end = (p.getEndedAt() == null) ? now : p.getEndedAt();
			return Math.max(0, Duration.between(p.getStartedAt(), end).getSeconds());
		}).sum();
		return Duration.ofSeconds(seconds);
	}

	@Transactional(readOnly = true)
	public ZonedDateTime deadline(Sla sla) {
		if (sla == null) {
			return null;
		}
		return sla.getStartDate()
				.plus(sla.getDuration())
				.plus(getPausedDuration(sla));
	}

	@Transactional(readOnly = true)
	public Duration remaining(Sla sla) {
		ZonedDateTime deadline = deadline(sla);
		if (deadline == null) {
			return Duration.ZERO;
		}
		return Duration.between(ZonedDateTime.now(), deadline);
	}

}
