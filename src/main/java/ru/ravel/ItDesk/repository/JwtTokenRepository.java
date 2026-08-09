package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.JwtTokenRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface JwtTokenRepository extends JpaRepository<JwtTokenRecord, Long> {

	Optional<JwtTokenRecord> findByJti(String jti);

	List<JwtTokenRecord> findAllByOrderByIssuedAtDesc();

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update JwtTokenRecord t
			set t.revokedAt = :revokedAt, t.revokedByUserId = :revokedByUserId
			where t.revokedAt is null and t.expiresAt > :now
			""")
	int revokeAllActive(
			@Param("revokedAt") Instant revokedAt,
			@Param("revokedByUserId") Long revokedByUserId,
			@Param("now") Instant now
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update JwtTokenRecord t
			set t.lastUsedAt = :usedAt,
				t.lastUsedIp = :ip,
				t.lastUserAgent = :userAgent,
				t.lastEndpoint = :endpoint,
				t.useCount = t.useCount + 1
			where t.id = :tokenId
			  and t.revokedAt is null
			  and t.expiresAt > :usedAt
			  and (t.lastUsedAt is null or t.lastUsedAt <= :updateBefore)
			""")
	int updateUsage(
			@Param("tokenId") Long tokenId,
			@Param("usedAt") Instant usedAt,
			@Param("updateBefore") Instant updateBefore,
			@Param("ip") String ip,
			@Param("userAgent") String userAgent,
			@Param("endpoint") String endpoint
	);
}
