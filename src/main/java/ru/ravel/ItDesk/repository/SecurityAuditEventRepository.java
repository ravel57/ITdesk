package ru.ravel.ItDesk.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.SecurityAuditEvent;

import java.time.ZonedDateTime;
import java.util.Collection;


@Repository
public interface SecurityAuditEventRepository extends JpaRepository<SecurityAuditEvent, Long> {

	String SEARCH_WHERE = """
			where (:allTypes = true or e.type in (:types))
			  and (cast(:search as text) is null
			       or lower(coalesce(e.actor, '')) like cast(:search as text)
			       or lower(coalesce(e.target, '')) like cast(:search as text)
			       or lower(coalesce(e.details, '')) like cast(:search as text)
			       or lower(coalesce(e.ip, '')) like cast(:search as text))
			  and e.created_at >= :from and e.created_at <= :to
			""";

	@Query(value = "select e.* from security_audit_event e " + SEARCH_WHERE + " order by e.created_at desc, e.id desc",
			countQuery = "select count(*) from security_audit_event e " + SEARCH_WHERE,
			nativeQuery = true)
	Page<SecurityAuditEvent> search(
			@Param("allTypes") boolean allTypes,
			@Param("types") Collection<String> types,
			@Param("search") String search,
			@Param("from") ZonedDateTime from,
			@Param("to") ZonedDateTime to,
			Pageable pageable
	);

	@Modifying
	@Query("delete from SecurityAuditEvent e where e.createdAt < :before")
	int deleteOlderThan(@Param("before") ZonedDateTime before);

}
