package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ravel.ItDesk.model.incident.IncidentSignal;
import ru.ravel.ItDesk.model.incident.IncidentSignalDecision;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

public interface IncidentSignalRepository extends JpaRepository<IncidentSignal, Long> {

	/**
	 * Сигналы-сбои по сервису и симптому в окне, ещё не отнесённые к инциденту.
	 */
	@Query("""
			select s from IncidentSignal s
			where s.serviceId = :serviceId
			  and s.symptom = :symptom
			  and s.createdAt >= :since
			  and s.decision in :decisions
			order by s.createdAt asc
			""")
	List<IncidentSignal> findWindow(
			@Param("serviceId") Long serviceId,
			@Param("symptom") String symptom,
			@Param("since") ZonedDateTime since,
			@Param("decisions") Collection<IncidentSignalDecision> decisions
	);

	/** Последнее срабатывание по сервису и симптому, по которому создан инцидент. */
	java.util.Optional<IncidentSignal> findFirstByServiceIdAndSymptomAndDecisionAndMatchedIncidentIdIsNotNullAndCreatedAtAfterOrderByCreatedAtDesc(
			Long serviceId,
			String symptom,
			IncidentSignalDecision decision,
			ZonedDateTime since
	);

	boolean existsByServiceIdAndSymptomAndDecisionAndMatchedIncidentIdIsNotNullAndCreatedAtAfter(
			Long serviceId,
			String symptom,
			IncidentSignalDecision decision,
			ZonedDateTime since
	);

	boolean existsByServiceIdAndSymptomAndDecisionAndCreatedAtAfter(
			Long serviceId,
			String symptom,
			IncidentSignalDecision decision,
			ZonedDateTime since
	);
}
