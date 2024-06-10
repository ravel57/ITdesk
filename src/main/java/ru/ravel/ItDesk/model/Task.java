package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;
import java.util.List;


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
	private String description;

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

	private boolean completed;

	private ZonedDateTime createdAt;

	private ZonedDateTime deadline;

	private Long linkedMessageId;

	@OneToOne
	private Sla sla;
}