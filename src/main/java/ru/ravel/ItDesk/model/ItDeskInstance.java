package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
public class ItDeskInstance {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Transient
	private ZonedDateTime createdAt;

	@Transient
	private ZonedDateTime validUntil;

	@Transient
	private Long usersCount;

	@Builder.Default
	private UUID license = UUID.randomUUID();

	private String name;
}
