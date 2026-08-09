package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;


@Entity
@Table(
		name = "user_notification_history",
		indexes = {
				@Index(name = "idx_user_notification_history_user_created", columnList = "user_id, created_at"),
				@Index(name = "idx_user_notification_history_user_read", columnList = "user_id, read_at")
		}
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserNotificationHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event", nullable = false, length = 64)
	private UserNotificationEvent event;

	@Column(name = "title", nullable = false, length = 160)
	private String title;

	@Column(name = "body", nullable = false, columnDefinition = "text")
	private String body;

	@Column(name = "source_message", columnDefinition = "text")
	private String sourceMessage;

	@Column(name = "created_at", nullable = false)
	private ZonedDateTime createdAt;

	@Column(name = "read_at")
	private ZonedDateTime readAt;


	@PrePersist
	private void beforeInsert() {
		if (createdAt == null) {
			createdAt = ZonedDateTime.now();
		}
	}
}
