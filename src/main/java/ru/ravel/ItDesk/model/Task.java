package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class Task {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	@Column(length = 1024)
	private String description;

	private String status;

	private String priority;

	@ManyToOne(targetEntity = User.class, cascade = CascadeType.ALL)
	private User executor;

	@OneToMany(targetEntity = Tag.class, fetch = FetchType.EAGER)
	private List<Tag> tags;

	private boolean isCompleted;
}