package ru.ravel.ItDesk.reposetory;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ravel.ItDesk.models.Task;

public interface TaskRepository extends JpaRepository<Task, Long> {
}
