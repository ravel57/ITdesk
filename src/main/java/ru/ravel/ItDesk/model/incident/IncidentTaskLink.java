package ru.ravel.ItDesk.model.incident;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;

import java.time.ZonedDateTime;

@Entity
@Table(name = "support_incident_task_link")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class IncidentTaskLink {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "incident_id", nullable = false)
	private SupportIncident incident;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "task_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Task task;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "linked_by_id")
	private User linkedBy;

	@Column(name = "linked_at", nullable = false)
	@Builder.Default
	private ZonedDateTime linkedAt = ZonedDateTime.now();

	@Column(name = "primary_task", nullable = false)
	@Builder.Default
	private Boolean primaryTask = false;

	@Column(name = "recovery_confirmed", nullable = false)
	@Builder.Default
	private Boolean recoveryConfirmed = false;

	@Column(name = "recovery_confirmed_at")
	private ZonedDateTime recoveryConfirmedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "recovery_confirmed_by_id")
	private User recoveryConfirmedBy;

	@Column(name = "active", nullable = false)
	@Builder.Default
	private Boolean active = true;

	@Column(name = "unlinked_at")
	private ZonedDateTime unlinkedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "unlinked_by_id")
	private User unlinkedBy;
}
