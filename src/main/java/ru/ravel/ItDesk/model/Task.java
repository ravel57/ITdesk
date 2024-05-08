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
public class Task {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	@Column(length = 1024)
	private String description;

	@ManyToOne(fetch = FetchType.EAGER)
	private Status status;

	private String priority;

	@ManyToOne(fetch = FetchType.EAGER)
	private User executor;

	@ManyToMany(fetch = FetchType.EAGER)
	private List<Tag> tags;

	private boolean isCompleted;

	private ZonedDateTime createdAt;

	private ZonedDateTime deadline;
}