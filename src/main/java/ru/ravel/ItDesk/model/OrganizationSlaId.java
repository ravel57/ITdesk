package ru.ravel.ItDesk.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;


@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationSlaId implements Serializable {

	@Column(name = "organization_id")
	private Long organizationId;

	@Column(name = "sla_key")
	private Long priorityId;
}