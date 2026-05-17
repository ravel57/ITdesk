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
import java.util.List;


@Service
@RequiredArgsConstructor
public class SlaService {

	public static final String AUTO_NON_WORKING_TIME_PAUSE_REASON = "Авто-пауза SLA: нерабочее время";

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
		List<SlaPause> activePauses = slaPauseRepository.findAllBySlaIdAndEndedAtIsNull(sla.getId());
		if (activePauses.isEmpty()) {
			return;
		}
		ZonedDateTime now = ZonedDateTime.now();
		activePauses.forEach(pause -> pause.setEndedAt(now));
		slaPauseRepository.saveAll(activePauses);
	}

	@Transactional
	public void pauseForNonWorkingTime(Sla sla) {
		if (sla == null || sla.getId() == null) {
			return;
		}

		boolean alreadyPaused = slaPauseRepository.existsBySlaIdAndEndedAtIsNull(sla.getId());

		if (alreadyPaused) {
			return;
		}

		SlaPause pause = SlaPause.builder()
				.sla(sla)
				.startedAt(ZonedDateTime.now())
				.endedAt(null)
				.reason(AUTO_NON_WORKING_TIME_PAUSE_REASON)
				.build();

		slaPauseRepository.saveAndFlush(pause);
	}

	@Transactional
	public void resumeNonWorkingTimePause(Sla sla) {
		if (sla == null || sla.getId() == null) {
			return;
		}

		List<SlaPause> activeAutoPauses = slaPauseRepository.findAllBySlaIdAndReasonAndEndedAtIsNull(
				sla.getId(),
				AUTO_NON_WORKING_TIME_PAUSE_REASON
		);

		if (activeAutoPauses.isEmpty()) {
			return;
		}

		ZonedDateTime now = ZonedDateTime.now();

		activeAutoPauses.forEach(pause -> pause.setEndedAt(now));
		slaPauseRepository.saveAll(activeAutoPauses);
	}

	@Transactional(readOnly = true)
	public boolean isNonWorkingTimePaused(Sla sla) {
		if (sla == null || sla.getId() == null) {
			return false;
		}

		return slaPauseRepository.existsBySlaIdAndReasonAndEndedAtIsNull(
				sla.getId(),
				AUTO_NON_WORKING_TIME_PAUSE_REASON
		);
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
				.mapToLong(pause -> {
					ZonedDateTime end = pause.getEndedAt() == null ? now : pause.getEndedAt();
					return Math.max(0, Duration.between(pause.getStartedAt(), end).getSeconds());
				})
				.sum();
		return Duration.ofSeconds(seconds);
	}


	@Transactional(readOnly = true)
	public ZonedDateTime deadline(Sla sla) {
		if (sla == null || sla.getStartDate() == null || sla.getDuration() == null) {
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
