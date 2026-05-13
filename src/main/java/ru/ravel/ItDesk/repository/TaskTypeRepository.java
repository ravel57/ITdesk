package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ravel.ItDesk.model.TaskType;

import java.util.Optional;

public interface TaskTypeRepository extends JpaRepository<TaskType, Long> {

	Optional<TaskType> findByType(String type);


	boolean existsByType(String type);

}