package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ravel.ItDesk.model.Event;


public interface AutomationOutboxRepository extends JpaRepository<Event, Long> {
}