package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.CompletedStatus;

@Repository
public interface CompletedStatusRepository extends JpaRepository<CompletedStatus, Long> {
}