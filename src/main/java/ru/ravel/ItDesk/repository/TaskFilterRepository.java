package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.TaskFilter;


@Repository
public interface TaskFilterRepository extends JpaRepository<TaskFilter, Long> {
}
