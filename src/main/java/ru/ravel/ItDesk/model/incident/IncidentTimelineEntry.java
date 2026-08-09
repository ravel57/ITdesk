package ru.ravel.ItDesk.model.incident;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import ru.ravel.ItDesk.model.User;

import java.time.ZonedDateTime;

@Entity
@Table(name = "support_incident_timeline_entry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class IncidentTimelineEntry {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incident_id", nullable = false)
	private SupportIncident incident;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	@Builder.Default
	private IncidentTimelineType type = IncidentTimelineType.NOTE;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	@Builder.Default
	private IncidentTimelineAudience audience = IncidentTimelineAudience.INTERNAL;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String text;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "author_id")
	private User author;

	@Column(name = "created_at", nullable = false)
	@Builder.Default
	private ZonedDateTime createdAt = ZonedDateTime.now();

	@Column(name = "template_key", length = 64)
	private String templateKey;
}
