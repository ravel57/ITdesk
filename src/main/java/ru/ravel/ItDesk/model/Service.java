package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jetbrains.annotations.NotNull;

import java.time.ZonedDateTime;
import java.util.*;

@Entity
@Table(name = "service", uniqueConstraints = {
		@UniqueConstraint(name = "uk_service_code", columnNames = "code")
})
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Service implements Comparable<Service> {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	@ToString.Include
	private Long id;

	@Column(nullable = false, length = 80)
	@ToString.Include
	private String code;

	@Column(nullable = false, length = 255)
	@ToString.Include
	private String name;

	@Column(columnDefinition = "TEXT")
	@Builder.Default
	private String description = "";

	/**
	 * Legacy monitoring link from the first version of the service entity.
	 * Zabbix polling uses {@link #zabbixIntegration} and
	 * {@link #monitoringIdentifiers}; the link is retained so existing data and
	 * external API clients are not broken during migration.
	 */
	@Column(name = "monitoring_url", length = 1024)
	private String monitoringUrl;

	@Column(nullable = false)
	@Builder.Default
	private Boolean active = true;

	@Column(name = "order_number", nullable = false)
	@Builder.Default
	private Integer orderNumber = 0;

	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "criticality_id")
	private ServiceCriticality criticality;

	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "owner_id")
	private User owner;

	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "default_support_line_id")
	private SupportLine defaultSupportLine;

	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "default_priority_id")
	private Priority defaultPriority;

	@JsonIgnore
	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "service_task_types",
			joinColumns = @JoinColumn(name = "service_id"),
			inverseJoinColumns = @JoinColumn(name = "task_type_id")
	)
	@Builder.Default
	private Set<TaskType> allowedTaskTypes = new LinkedHashSet<>();

	@JsonIgnore
	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "service_executors",
			joinColumns = @JoinColumn(name = "service_id"),
			inverseJoinColumns = @JoinColumn(name = "user_id")
	)
	@Builder.Default
	private Set<User> executors = new LinkedHashSet<>();

	@JsonIgnore
	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "service_dependencies",
			joinColumns = @JoinColumn(name = "service_id"),
			inverseJoinColumns = @JoinColumn(name = "depends_on_service_id")
	)
	@Builder.Default
	private Set<Service> dependentServices = new LinkedHashSet<>();

	@Column(name = "first_response_sla_minutes")
	private Integer firstResponseSlaMinutes;

	@Column(name = "resolution_sla_minutes")
	private Integer resolutionSlaMinutes;

	@Column(name = "support_calendar", length = 80)
	@Builder.Default
	private String supportCalendar = "GENERAL_SETTINGS";

	@Column(name = "pause_sla_on_waiting_client", nullable = false)
	@Builder.Default
	private Boolean pauseSlaOnWaitingClient = true;

	@Column(name = "auto_assign_default_line", nullable = false)
	@Builder.Default
	private Boolean autoAssignDefaultLine = true;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "priority_rules", columnDefinition = "jsonb")
	@Builder.Default
	private List<ServicePriorityRule> priorityRules = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "ola_stages", columnDefinition = "jsonb")
	@Builder.Default
	private List<ServiceOlaStage> olaStages = new ArrayList<>();

	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "zabbix_integration_id")
	private ZabbixIntegration zabbixIntegration;

	@Column(name = "zabbix_enabled", nullable = false)
	@Builder.Default
	private Boolean zabbixEnabled = false;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "monitoring_identifiers", columnDefinition = "jsonb")
	@Builder.Default
	private Map<String, String> monitoringIdentifiers = new LinkedHashMap<>();

	@Enumerated(EnumType.STRING)
	@Column(name = "monitoring_status", nullable = false, length = 32)
	@Builder.Default
	private ServiceMonitoringStatus monitoringStatus = ServiceMonitoringStatus.NOT_CONFIGURED;

	@Column(name = "last_monitoring_at")
	private ZonedDateTime lastMonitoringAt;

	@Column(name = "last_monitoring_message", length = 2048)
	private String lastMonitoringMessage;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "last_monitoring_data", columnDefinition = "jsonb")
	@Builder.Default
	private Map<String, Object> lastMonitoringData = new LinkedHashMap<>();

	@Override
	public int compareTo(@NotNull Service other) {
		int byOrder = Objects.requireNonNullElse(orderNumber, Integer.MAX_VALUE)
				.compareTo(Objects.requireNonNullElse(other == null ? null : other.orderNumber, Integer.MAX_VALUE));
		if (byOrder != 0) return byOrder;
		return Objects.toString(name, "").compareToIgnoreCase(Objects.toString(other == null ? null : other.name, ""));
	}
}
