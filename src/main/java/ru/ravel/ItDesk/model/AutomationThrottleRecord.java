package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;


@Entity
@Table(
		name = "automation_throttle_record",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_automation_throttle",
				columnNames = {"trigger_id", "node_id", "scope_key"}
		)
)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AutomationThrottleRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "trigger_id", nullable = false)
	private Long triggerId;

	@Column(name = "node_id", nullable = false)
	private String nodeId;

	@Column(name = "scope_key", nullable = false, length = 500)
	private String scopeKey;

	@Column(name = "last_executed_at", nullable = false)
	private Instant lastExecutedAt;
}
