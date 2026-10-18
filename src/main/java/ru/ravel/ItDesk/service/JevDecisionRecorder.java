package ru.ravel.ItDesk.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ravel.ItDesk.model.jev.JevDecision;
import ru.ravel.ItDesk.repository.JevDecisionRepository;

import java.time.ZonedDateTime;


/**
 * Пишет решения Jev в журнал (таблица jev_decision) для страницы «Журнал решений» в настройках Jev.
 * Правило и действие автоматизации берутся из {@link #inAction}, который выставляет исполнитель автоматизаций.
 * Запись — в отдельной транзакции: сбой журнала не влияет на автоматизацию и не теряется при её откате.
 */
@Service
public class JevDecisionRecorder {

	private static final Logger log = LoggerFactory.getLogger("JEV");
	private static final int RETENTION_DAYS = 30;
	private static final ThreadLocal<ActionContext> CURRENT = new ThreadLocal<>();

	private final JevDecisionRepository repository;
	private final TransactionTemplate newTransaction;


	public JevDecisionRecorder(JevDecisionRepository repository, PlatformTransactionManager transactionManager) {
		this.repository = repository;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public enum Outcome {
		ACCEPTED,
		REJECTED,
		NONE,
		UNDECIDED
	}


	public record ActionContext(Long ruleId, String ruleName, String action) {
	}


	/**
	 * Выполняет действие автоматизации с контекстом правила для журнала.
	 */
	public static void inAction(ActionContext context, Runnable action) {
		ActionContext previous = CURRENT.get();
		CURRENT.set(context);
		try {
			action.run();
		} finally {
			if (previous == null) {
				CURRENT.remove();
			} else {
				CURRENT.set(previous);
			}
		}
	}


	public void record(String context, String question, String choice, double confidence, Double threshold, Outcome outcome) {
		ActionContext action = CURRENT.get();
		try {
			// отдельная транзакция через шаблон: исключение не помечает транзакцию автоматизации rollback-only
			newTransaction.executeWithoutResult(status -> repository.save(JevDecision.builder()
					.createdAt(ZonedDateTime.now())
					.ruleId(action == null ? null : action.ruleId())
					.ruleName(action == null ? null : limit(action.ruleName(), 255))
					.action(action == null ? null : limit(action.action(), 64))
					.context(limit(context, 255))
					.question(limit(question, 64))
					.choice(limit(choice, 255))
					.confidence(confidence)
					.threshold(threshold)
					.outcome(outcome.name())
					.build()));
		} catch (Exception e) {
			log.warn("Unable to write Jev decision log: {}", e.getMessage());
		}
	}


	@Scheduled(cron = "0 30 3 * * *")
	@Transactional
	public void cleanup() {
		int removed = repository.deleteOlderThan(ZonedDateTime.now().minusDays(RETENTION_DAYS));
		if (removed > 0) {
			log.info("Jev decision log: removed {} records older than {} days", removed, RETENTION_DAYS);
		}
	}


	private String limit(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max);
	}

}
