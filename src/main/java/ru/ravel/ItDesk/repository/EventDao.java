package ru.ravel.ItDesk.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.automatosation.OutboxStatus;

import java.time.Instant;
import java.util.List;


@Repository
@RequiredArgsConstructor
public class EventDao {

	@PersistenceContext
	private final EntityManager em;

	/**
	 * В одной короткой транзакции:
	 * 1) лочим строки (skip locked)
	 * 2) помечаем PROCESSING
	 * Возвращаем id для дальнейшей обработки без удержания блокировок.
	 */
	@Transactional
	public List<Long> fetchAndMarkProcessing(int limit) {
		@SuppressWarnings("unchecked")
		List<Long> ids = em.createNativeQuery("""
						select id
						from event
						where status = 'NEW' and available_at <= now()
						order by created_at
						for update skip locked
						limit :lim
						""")
				.setParameter("lim", limit)
				.getResultList();
		if (ids.isEmpty()) {
			return ids;
		}
		em.createQuery("""
						update Event e
						set e.status = :st, e.updatedAt = :now
						where e.id in :ids
						""")
				.setParameter("st", OutboxStatus.PROCESSING)
				.setParameter("now", Instant.now())
				.setParameter("ids", ids)
				.executeUpdate();
		return ids;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markDone(Long id) {
		em.createQuery("""
						update Event e
						set e.status = :st, e.updatedAt = :now
						where e.id = :id
						""")
				.setParameter("st", OutboxStatus.DONE)
				.setParameter("now", Instant.now())
				.setParameter("id", id)
				.executeUpdate();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(Long id, Exception e) {
		em.createQuery("""
						update AutomationOutboxEvent e
						set e.status = :st,
						    e.updatedAt = :now,
						    e.lastError = :err
						where e.id = :id
						""")
				.setParameter("st", OutboxStatus.FAILED)
				.setParameter("now", Instant.now())
				.setParameter("err", trimError(e))
				.setParameter("id", id)
				.executeUpdate();
	}

	private String trimError(Exception e) {
		String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
		return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
	}
}