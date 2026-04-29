package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Task;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;


@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

	@Query("""
			select t from Task t
			left join fetch t.sla s
			left join fetch s.pauses
			where t.id = :id
			""")
	Optional<Task> findByIdWithSla(Long id);


	@Query("""
			select t
			from Task t
			where t.deadline is not null
			  and t.deadline < :now
			  and coalesce(t.completed, false) = false
			""")
	List<Task> findOverdueNotCompleted(ZonedDateTime now);


	@Query("""
			select distinct t
			from Task t
			left join fetch t.sla s
			left join fetch s.pauses
			where coalesce(t.completed, false) = false
			  and t.sla is not null
			""")
	List<Task> findAllActiveWithSla();

}
