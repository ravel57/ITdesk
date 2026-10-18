package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.LocalTime;


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

	@Enumerated(EnumType.STRING)
	@Column(name = "support_line_access_mode", nullable = false, length = 32, columnDefinition = "varchar(32) default 'HYBRID'")
	@Builder.Default
	private SupportLineAccessMode supportLineAccessMode = SupportLineAccessMode.HYBRID;

	@JsonIgnore
	@ToString.Exclude
	@Column(name = "jwt_signing_secret", length = 256)
	private String jwtSigningSecret;

	/** Обязательна ли двухфакторная аутентификация для администраторов. */
	@Column(name = "mfa_required_for_admins", nullable = false, columnDefinition = "boolean default true")
	@Builder.Default
	private Boolean mfaRequiredForAdmins = true;

	/** Разделы, открытые менеджеру организации (OBSERVER), — коды {@link ObserverFeature}. Меняются через /settings/observer-access. */
	@JsonIgnore
	@JdbcTypeCode(SqlTypes.JSON)
	// Значение по умолчанию: Hibernate (ddl-auto) добавляет колонку раньше Flyway, без него ALTER не проходит
	@Column(name = "observer_features", nullable = false,
			columnDefinition = "jsonb default '[\"TASKS\",\"INCIDENTS\",\"ANALYTICS\",\"SLA\",\"SERVICES\"]'::jsonb")
	@Builder.Default
	private java.util.Set<String> observerFeatures = new java.util.LinkedHashSet<>(
			java.util.Arrays.stream(ObserverFeature.values()).map(Enum::name).toList());



	public Duration getWorkdayDuration() {
		LocalTime start = LocalTime.parse(workdayStart);
		LocalTime end = LocalTime.parse(workdayEnd);
		if (end.equals(start)) {
			return Duration.ofHours(24);
		}
		if (end.isAfter(start)) {
			return Duration.between(start, end);
		}
		return Duration.between(start, LocalTime.MAX)
				.plusNanos(1)
				.plus(Duration.between(LocalTime.MIN, end));
	}

}