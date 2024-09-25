package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class License {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String name;

	@Transient
	private ZonedDateTime createdAt;

	@Transient
	private ZonedDateTime validUntil;

	@Transient
	private Long usersCount;

	@Builder.Default
	private UUID license = UUID.randomUUID();

	@Transient
	private String version;

}