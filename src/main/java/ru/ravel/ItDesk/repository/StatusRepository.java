package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Status;

@Repository
public interface StatusRepository extends JpaRepository<Status, Long> {
}
