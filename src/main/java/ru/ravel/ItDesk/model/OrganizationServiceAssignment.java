package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "organization_service_assignment", indexes = {
		@Index(name = "idx_org_service_assignment_org", columnList = "organization_id"),
		@Index(name = "idx_org_service_assignment_service", columnList = "service_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class OrganizationServiceAssignment {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "organization_id", nullable = false)
	private Organization organization;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "service_id", nullable = false)
	private Service service;

	@Column(name = "contract_number", length = 160)
	private String contractNumber;

	@Column(name = "tariff_name", length = 255)
	private String tariffName;

	@Column(name = "valid_from")
	private LocalDate validFrom;

	@Column(name = "valid_to")
	private LocalDate validTo;

	@Column(name = "support_calendar", length = 80)
	@Builder.Default
	private String supportCalendar = "GENERAL_SETTINGS";

	@Column(name = "sla_policy_name", length = 255)
	private String slaPolicyName;

	@Column(name = "first_response_sla_minutes")
	private Integer firstResponseSlaMinutes;

	@Column(name = "resolution_sla_minutes")
	private Integer resolutionSlaMinutes;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "available_channels", columnDefinition = "jsonb")
	@Builder.Default
	private List<String> availableChannels = new ArrayList<>();

	@Column(nullable = false)
	@Builder.Default
	private Boolean active = true;

	@Column(name = "default_for_organization", nullable = false)
	@Builder.Default
	private Boolean defaultForOrganization = false;

	@Column(columnDefinition = "TEXT")
	private String notes;

	@JsonIgnore
	public boolean isEffectiveOn(LocalDate date) {
		LocalDate value = date == null ? LocalDate.now() : date;
		return !Boolean.FALSE.equals(active)
				&& (validFrom == null || !value.isBefore(validFrom))
				&& (validTo == null || !value.isAfter(validTo));
	}
}
