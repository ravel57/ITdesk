package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.repository.EventDao;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;


@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationWorker {

	private final EventDao outboxDao;
	private final AutomationItemProcessor automationItemProcessor;

	private static final long LOG_INTERVAL_MS = 5 * 60 * 1000L;
	private static final AtomicLong nextAllowedLogAtMs = new AtomicLong(0);
	private final Logger logger = LoggerFactory.getLogger(this.getClass());


	@Scheduled(fixedDelayString = "${automation.worker.delay-ms:200}")
	public void poll() {
		List<Long> ids = outboxDao.fetchAndMarkProcessing(50);
		for (Long id : ids) {
			try {
				automationItemProcessor.processOneTx(id);
			} catch (Throwable e) {
				logThrottled(e);
			}
		}
	}


	private void logThrottled(Throwable e) {
		long now = System.currentTimeMillis();
		long prev = nextAllowedLogAtMs.getAndUpdate(cur -> (now >= cur) ? now + LOG_INTERVAL_MS : cur);

		if (now >= prev) {
			logger.error("[AUTOMATION_WORKER_THROTTLED] {}", e.getMessage(), e);
		}
	}
}
