package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.repository.AutomationWorkflowRunDao;

import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationWorkflowWorker {

	private final AutomationWorkflowRunDao workflowRunDao;
	private final AutomationWorkflowRunProcessor workflowRunProcessor;


	@Scheduled(fixedDelayString = "${automation.workflow.worker.delay-ms:500}")
	public void poll() {
		List<Long> runIds = workflowRunDao.fetchAndMarkProcessing(30);
		for (Long runId : runIds) {
			try {
				workflowRunProcessor.processOneTx(runId);
			} catch (Exception e) {
				log.warn("Delayed automation run failed, runId={}: {}", runId, e.getMessage());
			}
		}
	}
}
