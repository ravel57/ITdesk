package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Knowledge;
import ru.ravel.ItDesk.model.KnowledgeStatus;

import java.util.List;

@Repository
public interface KnowledgeRepository extends JpaRepository<Knowledge, Long> {

	List<Knowledge> findAllByStatus(KnowledgeStatus status);

	boolean existsBySourceTaskId(Long sourceTaskId);

	@Query("select coalesce(max(k.orderNumber), -1) from Knowledge k")
	int maxOrderNumber();

}
