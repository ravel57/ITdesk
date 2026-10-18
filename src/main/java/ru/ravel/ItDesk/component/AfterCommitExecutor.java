package ru.ravel.ItDesk.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Set;


/**
 * Выполняет действие после коммита текущей транзакции — для realtime-уведомлений (WebSocket),
 * которые не должны уходить раньше, чем данные видны в БД, и не должны уходить при откате.
 * <p>
 * Действие выполняется в отдельной транзакции (REQUIRES_NEW): после коммита исходная транзакция
 * уже завершена, и чтение/запись в ней не сохраняются. Ошибки действия логируются и не пробрасываются.
 * Вне транзакции действие выполняется сразу.
 */
@Component
public class AfterCommitExecutor {

	private static final Logger log = LoggerFactory.getLogger(AfterCommitExecutor.class);
	private static final Object KEYS_RESOURCE = new Object();

	private final TransactionTemplate newTransaction;


	public AfterCommitExecutor(PlatformTransactionManager transactionManager) {
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}


	public void run(Runnable action) {
		runOnce(null, action);
	}


	/**
	 * То же, но действие с одинаковым ключом выполняется один раз за транзакцию
	 * (например, одно событие «заявка обновлена» на несколько изменений одной заявки).
	 */
	@SuppressWarnings("unchecked")
	public void runOnce(String key, Runnable action) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			execute(action);
			return;
		}
		if (key != null) {
			Set<String> keys = (Set<String>) TransactionSynchronizationManager.getResource(KEYS_RESOURCE);
			if (keys == null) {
				keys = new HashSet<>();
				TransactionSynchronizationManager.bindResource(KEYS_RESOURCE, keys);
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override
					public void afterCompletion(int status) {
						TransactionSynchronizationManager.unbindResourceIfPossible(KEYS_RESOURCE);
					}
				});
			}
			if (!keys.add(key)) {
				return;
			}
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				execute(action);
			}
		});
	}


	private void execute(Runnable action) {
		try {
			newTransaction.executeWithoutResult(status -> action.run());
		} catch (Exception e) {
			// ERROR, а не WARN: для ru.ravel.ItDesk в logback включён только ERROR, иначе сбой realtime не виден
			log.error("After-commit action failed", e);
		}
	}

}
