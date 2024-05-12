package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Priority;

@Repository
public interface PriorityRepository extends JpaRepository<Priority, Long> {
}
