package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Support;

@Repository
public interface SupportRepository extends JpaRepository<Support, Long> {
}
