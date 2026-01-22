package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;


@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Incident {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String description;

	@ManyToOne(fetch = FetchType.EAGER)
	private Organization organization;

	@ManyToMany(fetch = FetchType.EAGER)
	private List<Service> service;
}
