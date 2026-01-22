package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.ravel.ItDesk.model.automatosation.OutboxStatus;
import ru.ravel.ItDesk.model.automatosation.TriggerType;

import java.time.Instant;

@Entity
@Table(
		name = "event", indexes = {
		@Index(name = "idx_outbox_status_time", columnList = "status, available_at, created_at"),
		@Index(name = "idx_outbox_org_trigger", columnList = "trigger_type")
})
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Events {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	private TriggerType triggerType;

	@JdbcTypeCode(SqlTypes.JSON)
	private JsonNode payload;

	@Enumerated(EnumType.STRING)
	private OutboxStatus status;

	private int retries;

	private Instant availableAt;

	private Instant createdAt;

	private Instant updatedAt;

	private String lastError;


	@PrePersist
	void prePersist() {
		Instant now = Instant.now();
		if (status == null) {
			status = OutboxStatus.NEW;
		}
		if (availableAt == null) {
			availableAt = now;
		}
		if (createdAt == null) {
			createdAt = now;
		}
		updatedAt = now;
	}


	@PreUpdate
	void preUpdate() {
		updatedAt = Instant.now();
	}

}