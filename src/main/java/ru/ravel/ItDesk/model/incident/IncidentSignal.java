package ru.ravel.ItDesk.model.incident;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;


/**
 * Разметка входящего сообщения детектором массовых инцидентов Jev.
 * Текст сообщения не копируется: при проверке пачки он читается из message по message_id.
 */
@Entity
@Table(name = "incident_signal")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class IncidentSignal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "message_id", nullable = false)
	private Long messageId;

	@Column(name = "client_id", nullable = false)
	private Long clientId;

	@Column(name = "organization_id")
	private Long organizationId;

	@Column(name = "service_id")
	private Long serviceId;

	@Column(length = 64)
	private String symptom;

	@Column(name = "outage_probability", nullable = false)
	private Double outageProbability;

	@Column(name = "impact_score")
	private Double impactScore;

	@Column(name = "matched_incident_id")
	private Long matchedIncidentId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private IncidentSignalDecision decision;

	@Column(name = "created_at", nullable = false)
	private ZonedDateTime createdAt;
}
