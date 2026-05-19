package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;


@Entity
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Organization implements Comparable<Organization>, Serializable {
	@Serial
	private static final long serialVersionUID = 46782534215464L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	protected Long id;

	protected String name;

	@Column(nullable = false, columnDefinition = "int default 0")
	protected Integer orderNumber;

	@Builder.Default
	protected Boolean active = true;

	protected String inn;

	protected String kpp;

	protected String externalId;

	protected String mainAddress;

	@Builder.Default
	protected String priorityLevel = "NORMAL";

	protected String managerName;

	protected String managerPhone;

	protected String managerEmail;

	protected String contractNumber;

	protected LocalDate contractStartDate;

	protected LocalDate contractEndDate;

	protected String tariffName;

	protected String servicePackageName;

	@Column(precision = 19, scale = 2)
	protected BigDecimal monthlyFee;

	@Column(columnDefinition = "TEXT")
	protected String description;

	@Builder.Default
	protected Boolean useVisitsLimit = false;

	@Builder.Default
	protected Integer monthlyVisitsLimit = 0;

	@Builder.Default
	protected Integer visitsUsed = 0;

	@Column(precision = 19, scale = 2)
	protected BigDecimal extraVisitPrice;

	@Column(precision = 19, scale = 2)
	protected BigDecimal urgentVisitPrice;

	@Builder.Default
	protected Integer visitResetDay = 1;

	@Builder.Default
	protected Boolean includedRemoteSupport = true;

	@Column(columnDefinition = "TEXT")
	protected String visitComment;

	protected String slaAgreementName;

	protected Integer slaFirstResponseMinutes;

	protected Integer slaResolutionHours;

	@Builder.Default
	protected String slaWorkCalendar = "GENERAL_SETTINGS";

	@Builder.Default
	protected Boolean pauseSlaOnWaitingClient = true;

	@Column(columnDefinition = "TEXT")
	protected String slaComment;

	@Column(columnDefinition = "TEXT")
	protected String serviceAddresses;

	@Column(columnDefinition = "TEXT")
	protected String communicationChannels;

	@Column(columnDefinition = "TEXT")
	protected String internalComment;


	@Override
	public int compareTo(@NotNull Organization o) {
		return orderNumber.compareTo(o.orderNumber);
	}
}