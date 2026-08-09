package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.OrganizationVisitReason;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationVisitReasonRepository extends JpaRepository<OrganizationVisitReason, Long> {

    List<OrganizationVisitReason> findAllByOrderByOrderNumberAscIdAsc();

    Optional<OrganizationVisitReason> findFirstByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
