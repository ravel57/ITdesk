package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.FrozenStatus;

@Repository
public interface FrozenStatusRepository extends JpaRepository<FrozenStatus, Long> {
}