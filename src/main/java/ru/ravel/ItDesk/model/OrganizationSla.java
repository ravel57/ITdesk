package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;


@Entity
@Table(name = "organization_sla")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationSla {

	@EmbeddedId
	private OrganizationSlaId id;

	@ManyToOne(fetch = FetchType.LAZY)
	@MapsId("organizationId")
	@JoinColumn(name = "organization_id")
	private Organization organization;

	@ManyToOne(fetch = FetchType.LAZY)
	@MapsId("priorityId")
	@JoinColumn(name = "sla_key")
	private Priority priority;

	@Column(name = "value")
	private BigDecimal value;

	@Enumerated(EnumType.STRING)
	@Column(name = "unit")
	private SlaUnit unit;

	public OrganizationSla(Organization organization, Priority priority, BigDecimal value, SlaUnit unit) {
		this.organization = organization;
		this.priority = priority;
		this.value = value;
		this.unit = unit;
		this.id = new OrganizationSlaId(organization.getId(), priority.getId());
	}
}