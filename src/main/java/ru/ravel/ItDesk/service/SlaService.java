package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.Sla;
import ru.ravel.ItDesk.model.SlaPause;
import ru.ravel.ItDesk.repository.SlaPauseRepository;
import ru.ravel.ItDesk.repository.SlaRepository;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;


@Service
@RequiredArgsConstructor
public class SlaService {

	private final SlaRepository slaRepository;
	private final SlaPauseRepository slaPauseRepository;


	@Transactional
	public void pause(Sla sla, String reason) {
		if (sla == null || sla.getId() == null) {
			return;
		}
		boolean alreadyPaused = slaPauseRepository.existsBySlaIdAndEndedAtIsNull(sla.getId());
		if (alreadyPaused) {
			return;
		}
		SlaPause pause = new SlaPause();
		pause.setSla(sla);
		pause.setStartedAt(ZonedDateTime.now());
		pause.setEndedAt(null);
		pause.setReason(reason);
		slaPauseRepository.saveAndFlush(pause);
	}


	@Transactional
	public void resume(Sla sla) {
		if (sla == null || sla.getId() == null) {
			return;
		}
		SlaPause active = slaPauseRepository.findFirstBySlaIdAndEndedAtIsNull(sla.getId())
				.orElse(null);
		if (active == null) {
			return;
		}
		active.setEndedAt(ZonedDateTime.now());
		slaPauseRepository.save(active);
	}


	@Transactional(readOnly = true)
	public boolean isPaused(Sla sla) {
		if (sla == null || sla.getId() == null) {
			return false;
		}
		return slaPauseRepository.existsBySlaIdAndEndedAtIsNull(sla.getId());
	}


	@Transactional(readOnly = true)
	public Duration getPausedDuration(Sla sla) {
		if (sla == null || sla.getId() == null) {
			return Duration.ZERO;
		}
		ZonedDateTime now = ZonedDateTime.now();
		long seconds = slaPauseRepository.findAllBySlaId(sla.getId()).stream()
				.mapToLong(p -> {
					ZonedDateTime end = p.getEndedAt() == null ? now : p.getEndedAt();
					return Math.max(0, Duration.between(p.getStartedAt(), end).getSeconds());
				})
				.sum();
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


	private Collection<SlaPause> pauses(Sla sla) {
		return sla.getPauses() == null ? Collections.emptyList() : sla.getPauses();
	}

}
