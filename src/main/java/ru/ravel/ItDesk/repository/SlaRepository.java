package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ravel.ItDesk.model.Sla;

public interface SlaRepository extends JpaRepository<Sla, Long> {
}
