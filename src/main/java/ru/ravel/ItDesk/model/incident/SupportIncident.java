package ru.ravel.ItDesk.model.incident;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.Service;
import ru.ravel.ItDesk.model.User;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "support_incident")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SupportIncident {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "incident_number", unique = true, length = 32)
	private String incidentNumber;

	@Column(nullable = false, length = 512)
	private String title;

	@Column(columnDefinition = "TEXT")
	@Builder.Default
	private String description = "";

	@Column(name = "public_description", columnDefinition = "TEXT")
	@Builder.Default
	private String publicDescription = "";

	@Column(name = "impact_summary", columnDefinition = "TEXT")
	@Builder.Default
	private String impactSummary = "";

	/** Legacy fallback kept for backward-compatible API/data migration. */
	@Column(name = "service_name", length = 255)
	private String serviceName;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "support_incident_services",
			joinColumns = @JoinColumn(name = "incident_id"),
			inverseJoinColumns = @JoinColumn(name = "service_id")
	)
	@Builder.Default
	private Set<Service> services = new LinkedHashSet<>();

	@Column(columnDefinition = "TEXT")
	private String workaround;

	@Column(name = "root_cause", columnDefinition = "TEXT")
	private String rootCause;

	@Column(columnDefinition = "TEXT")
	private String resolution;

	@Column(name = "postmortem_summary", columnDefinition = "TEXT")
	private String postmortemSummary;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	@Builder.Default
	private IncidentSeverity severity = IncidentSeverity.P3;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	@Builder.Default
	private IncidentStatus status = IncidentStatus.DRAFT;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "leader_id")
	private User leader;

	@Column(name = "leader_assigned_at")
	private ZonedDateTime leaderAssignedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "technical_owner_id")
	private User technicalOwner;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "communication_owner_id")
	private User communicationOwner;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "support_line_id")
	private SupportLine supportLine;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_id")
	private User createdBy;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "support_incident_participants",
			joinColumns = @JoinColumn(name = "incident_id"),
			inverseJoinColumns = @JoinColumn(name = "user_id")
	)
	@Builder.Default
	private Set<User> participants = new LinkedHashSet<>();

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "support_incident_observers",
			joinColumns = @JoinColumn(name = "incident_id"),
			inverseJoinColumns = @JoinColumn(name = "user_id")
	)
	@Builder.Default
	private Set<User> observers = new LinkedHashSet<>();

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "support_incident_organizations",
			joinColumns = @JoinColumn(name = "incident_id"),
			inverseJoinColumns = @JoinColumn(name = "organization_id")
	)
	@Builder.Default
	private Set<Organization> organizations = new LinkedHashSet<>();

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "support_incident_clients",
			joinColumns = @JoinColumn(name = "incident_id"),
			inverseJoinColumns = @JoinColumn(name = "client_id")
	)
	@Builder.Default
	private Set<Client> clients = new LinkedHashSet<>();

	@Column(name = "actual_started_at")
	private ZonedDateTime actualStartedAt;

	@Column(name = "detected_at")
	private ZonedDateTime detectedAt;

	@Column(name = "declared_at")
	private ZonedDateTime declaredAt;

	@Column(name = "mitigated_at")
	private ZonedDateTime mitigatedAt;

	@Column(name = "restored_at")
	private ZonedDateTime restoredAt;

	@Column(name = "closed_at")
	private ZonedDateTime closedAt;

	@Column(name = "next_update_at")
	private ZonedDateTime nextUpdateAt;

	@Column(name = "last_published_update_at")
	private ZonedDateTime lastPublishedUpdateAt;

	@Column(name = "communication_overdue_notified_at")
	private ZonedDateTime communicationOverdueNotifiedAt;

	@Column(name = "update_interval_minutes", nullable = false)
	@Builder.Default
	private Integer updateIntervalMinutes = 30;

	@Column(name = "planned_updates_count", nullable = false)
	@Builder.Default
	private Integer plannedUpdatesCount = 0;

	@Column(name = "on_time_updates_count", nullable = false)
	@Builder.Default
	private Integer onTimeUpdatesCount = 0;

	@Column(name = "created_at", nullable = false)
	@Builder.Default
	private ZonedDateTime createdAt = ZonedDateTime.now();

	@Column(name = "updated_at", nullable = false)
	@Builder.Default
	private ZonedDateTime updatedAt = ZonedDateTime.now();

	@JsonIgnore
	@OneToMany(mappedBy = "incident", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
	@Builder.Default
	private List<IncidentTaskLink> taskLinks = new ArrayList<>();

	@JsonIgnore
	@OneToMany(mappedBy = "incident", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
	@Builder.Default
	private List<IncidentTimelineEntry> timeline = new ArrayList<>();
}
