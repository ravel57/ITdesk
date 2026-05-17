package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AppSettings {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(nullable = false)
	@Builder.Default
	private String timezone = "Europe/Moscow";

	@Column(nullable = false)
	@Builder.Default
	private Boolean workingTimeEnabled = true;

	@Column(nullable = false, length = 5)
	@Builder.Default
	private String workdayStart = "09:00";

	@Column(nullable = false, length = 5)
	@Builder.Default
	private String workdayEnd = "18:00";

	@Column(nullable = false)
	@Builder.Default
	private Boolean mondayEnabled = true;

	@Column(nullable = false)
	@Builder.Default
	private Boolean tuesdayEnabled = true;

	@Column(nullable = false)
	@Builder.Default
	private Boolean wednesdayEnabled = true;

	@Column(nullable = false)
	@Builder.Default
	private Boolean thursdayEnabled = true;

	@Column(nullable = false)
	@Builder.Default
	private Boolean fridayEnabled = true;

	@Column(nullable = false)
	@Builder.Default
	private Boolean saturdayEnabled = false;

	@Column(nullable = false)
	@Builder.Default
	private Boolean sundayEnabled = false;
}