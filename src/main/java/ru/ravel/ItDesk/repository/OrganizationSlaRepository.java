package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ravel.ItDesk.model.OrganizationSla;
import ru.ravel.ItDesk.model.OrganizationSlaId;
import ru.ravel.ItDesk.model.SlaUnit;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface OrganizationSlaRepository extends JpaRepository<OrganizationSla, OrganizationSlaId> {

	List<OrganizationSla> findAllByOrganizationId(Long organizationId);

	List<OrganizationSla> findAllByOrganizationIdIn(Collection<Long> organizationIds);

	@Modifying
	@Query("""
            update OrganizationSla os
            set os.value = :value,
                os.unit = :unit
            where os.id.organizationId = :organizationId
              and os.id.priorityId = :priorityId
            """)
	int updateSlaValue(
			@Param("organizationId") Long organizationId,
			@Param("priorityId") Long priorityId,
			@Param("value") BigDecimal value,
			@Param("unit") SlaUnit unit
	);
}