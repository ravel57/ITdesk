package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Task;

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

}
