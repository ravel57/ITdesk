package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
public class Sla {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private ZonedDateTime startDate;

	private Duration duration;

	@JsonIgnore
	@OneToMany(mappedBy = "sla", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<SlaPause> pauses = new ArrayList<>();
}
