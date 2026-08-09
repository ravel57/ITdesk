package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.TaskFilter;
import ru.ravel.ItDesk.model.TaskFilterScope;

import java.util.List;


@Repository
public interface TaskFilterRepository extends JpaRepository<TaskFilter, Long> {
	List<TaskFilter> findAllByScopeOrOwnerUserIdOrderByLabelAsc(TaskFilterScope scope, Long ownerUserId);

	List<TaskFilter> findAllByScopeAndOwnerUserIdOrderByLabelAsc(TaskFilterScope scope, Long ownerUserId);
}
