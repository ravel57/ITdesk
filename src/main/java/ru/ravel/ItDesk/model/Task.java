package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

import ru.ravel.ItDesk.dto.OlaInfoDto;


@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Task {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String name;

	@Column(length = 1024)
	@Builder.Default
	private String description = "";

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "type_id")
	private TaskType type;

	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	private List<ChecklistItem> checklist = new ArrayList<>();

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn
	private Status status;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn
	private Priority priority;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn
	private User executor;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "support_line_id")
	private SupportLine supportLine;

	private ZonedDateTime enteredCurrentLineAt;

	private ZonedDateTime olaDeadline;

	private ZonedDateTime olaWarningAt;

	@Enumerated(EnumType.STRING)
	@Column(length = 24)
	@Builder.Default
	private OlaStatus olaStatus = OlaStatus.DISABLED;

	private Long olaDurationSeconds;

	@Builder.Default
	private Boolean olaUseWorkingTime = false;

	private ZonedDateTime olaPausedAt;

	private Long olaRemainingSecondsOnPause;

	@Transient
	private OlaInfoDto olaInfo;

	@JsonIgnore
	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "task_access_users",
			joinColumns = @JoinColumn(name = "task_id"),
			inverseJoinColumns = @JoinColumn(name = "user_id")
	)
	@Builder.Default
	private Set<User> accessUsers = new LinkedHashSet<>();

	@ManyToMany(fetch = FetchType.EAGER)
	private List<Tag> tags;

	@Builder.Default
	private Boolean completed = false;

	@Builder.Default
	private Boolean frozen = false;

	private ZonedDateTime frozenUntil;

	private ZonedDateTime frozenFrom;

	@ManyToOne(fetch = FetchType.EAGER)
	private Status previousStatus;

	@Builder.Default
	private ZonedDateTime createdAt =  ZonedDateTime.now();

	private ZonedDateTime closedAt;

	private ZonedDateTime deadline;

	private ZonedDateTime lastActivity;

	private Long linkedMessageId;

	@JsonProperty(access = JsonProperty.Access.READ_ONLY)
	@OneToOne(fetch = FetchType.EAGER)
	private Sla sla;

	@OneToMany(fetch = FetchType.EAGER/*, orphanRemoval = true*/)
	@JoinColumn(name = "task_id")
	@Builder.Default
	private List<Message> messages = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	// Long = User.id; Boolean = isPinged
	private Map<Long, Boolean> unreadPingTasksMessages = new HashMap<>();

	private String statusChangeReason;
}