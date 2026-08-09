package ru.ravel.ItDesk.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.UserNotificationHistory;

import java.time.ZonedDateTime;
import java.util.Optional;


@Repository
public interface UserNotificationHistoryRepository extends JpaRepository<UserNotificationHistory, Long> {

	Page<UserNotificationHistory> findAllByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

	Page<UserNotificationHistory> findAllByUserIdAndReadAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable);

	long countByUserIdAndReadAtIsNull(Long userId);

	Optional<UserNotificationHistory> findByIdAndUserId(Long id, Long userId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update UserNotificationHistory n set n.readAt = :readAt where n.userId = :userId and n.readAt is null")
	int markAllAsRead(@Param("userId") Long userId, @Param("readAt") ZonedDateTime readAt);
}
