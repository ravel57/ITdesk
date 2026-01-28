package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;


@Entity
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SlaPause {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	@JoinColumn(name = "sla_id")
	private Sla sla;

	@Column(nullable = false)
	private ZonedDateTime startedAt;

	@Column
	private ZonedDateTime endedAt; // null = еще на паузе

	@Column
	private String reason;
}