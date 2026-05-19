package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrganizationVisit implements Serializable {
	@Serial
	private static final long serialVersionUID = 46782534215465L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "organization_id", nullable = false)
	@JsonIgnore
	private Organization organization;

	@Column(nullable = false)
	private LocalDateTime visitDate;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	private String type;

	@Column(columnDefinition = "TEXT")
	private String comment;

	private Long taskId;

	private String taskTitle;

	@Builder.Default
	@Column(nullable = false, columnDefinition = "boolean default true")
	private Boolean countedInPackage = true;

	@Builder.Default
	@Column(nullable = false, columnDefinition = "boolean default false")
	private Boolean overLimit = false;

	@Column(precision = 19, scale = 2)
	private BigDecimal price;

	private Integer visitsUsedAfter;

	private Integer monthlyVisitsLimitSnapshot;

	private String createdBy;

	@PrePersist
	public void prePersist() {
		if (createdAt == null) {
			createdAt = LocalDateTime.now();
		}
		if (visitDate == null) {
			visitDate = LocalDateTime.now();
		}
		if (countedInPackage == null) {
			countedInPackage = true;
		}
		if (overLimit == null) {
			overLimit = false;
		}
	}
}