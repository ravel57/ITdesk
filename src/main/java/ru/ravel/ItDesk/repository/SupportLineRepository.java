package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.SupportLine;

import java.util.Optional;


@Repository
public interface SupportLineRepository extends JpaRepository<SupportLine, Long> {

    Optional<SupportLine> findByDefaultSelectionTrue();

    Optional<SupportLine> findFirstByActiveTrueOrderByOrderNumberAsc();

    Optional<SupportLine> findByName(String name);

    boolean existsByNameIgnoreCase(String name);
}
