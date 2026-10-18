package ru.ravel.ItDesk.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AfterCommitExecutorTest {

	private final AfterCommitExecutor executor = new AfterCommitExecutor(mock(PlatformTransactionManager.class));

	@AfterEach
	void tearDown() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void runsOnceAfterCommit() {
		AtomicInteger calls = new AtomicInteger();
		TransactionSynchronizationManager.initSynchronization();
		executor.runOnce("task:1", calls::incrementAndGet);
		executor.runOnce("task:1", calls::incrementAndGet);
		executor.runOnce("task:2", calls::incrementAndGet);
		assertEquals(0, calls.get(), "nothing before commit");

		var synchronizations = TransactionSynchronizationManager.getSynchronizations();
		TransactionSynchronizationUtils.invokeAfterCommit(synchronizations);
		TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations, TransactionSynchronization.STATUS_COMMITTED);
		assertEquals(2, calls.get(), "one per key");
	}

	@Test
	void skippedOnRollback() {
		AtomicInteger calls = new AtomicInteger();
		TransactionSynchronizationManager.initSynchronization();
		executor.runOnce("incident:7", calls::incrementAndGet);
		TransactionSynchronizationUtils.invokeAfterCompletion(
				TransactionSynchronizationManager.getSynchronizations(), TransactionSynchronization.STATUS_ROLLED_BACK);
		assertEquals(0, calls.get());
	}

	@Test
	void runsImmediatelyWithoutTransaction() {
		AtomicInteger calls = new AtomicInteger();
		executor.run(calls::incrementAndGet);
		assertEquals(1, calls.get());
	}
}
