package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * Проблема Zabbix, полученная веб-хуком. Открытые проблемы сервиса определяют его состояние мониторинга.
 */
@Entity
@Table(name = "monitoring_problem", uniqueConstraints = {
		@UniqueConstraint(name = "uk_monitoring_problem_event", columnNames = {"integration_id", "event_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MonitoringProblem {

	public enum Status {OPEN, RESOLVED}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "integration_id", nullable = false)
	@ToString.Exclude
	private ZabbixIntegration integration;

	@Column(name = "event_id", nullable = false, length = 64)
	private String eventId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "service_id")
	@ToString.Exclude
	private Service service;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Status status;

	@Column(length = 1024)
	private String name;

	/** Важность Zabbix: 0 Not classified … 5 Disaster */
	@Column(nullable = false)
	@Builder.Default
	private Integer severity = 0;

	@Column(name = "severity_name", length = 64)
	private String severityName;

	@Column(length = 255)
	private String host;

	@Column(name = "host_name", length = 255)
	private String hostName;

	@Column(name = "trigger_id", length = 64)
	private String triggerId;

	@Column(nullable = false)
	@Builder.Default
	private Boolean acknowledged = false;

	@Column(length = 1024)
	private String opdata;

	@Column(length = 2048)
	private String url;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	@Builder.Default
	private List<Map<String, String>> tags = new ArrayList<>();

	@Column(name = "started_at")
	private ZonedDateTime startedAt;

	@Column(name = "resolved_at")
	private ZonedDateTime resolvedAt;

	@Column(name = "received_at", nullable = false)
	private ZonedDateTime receivedAt;

	@Column(name = "updated_at", nullable = false)
	private ZonedDateTime updatedAt;
}
