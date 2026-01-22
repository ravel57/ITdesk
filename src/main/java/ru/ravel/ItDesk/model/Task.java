package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


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
	@JoinColumn
	private Status status;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn
	private Priority priority;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn
	private User executor;

	@ManyToMany(fetch = FetchType.EAGER)
	private List<Tag> tags;

	@Builder.Default
	private Boolean completed = false;

	@Builder.Default
	private Boolean frozen = false;

	private ZonedDateTime frozenUntil;

	private ZonedDateTime frozenFrom;

	@ManyToOne(fetch = FetchType.EAGER)
	private Status previusStatus;

	@Builder.Default
	private ZonedDateTime createdAt =  ZonedDateTime.now();

	private ZonedDateTime deadline;

	private ZonedDateTime lastActivity;

	private Long linkedMessageId;

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
}