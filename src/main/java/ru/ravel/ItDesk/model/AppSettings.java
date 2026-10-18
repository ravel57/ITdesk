package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;


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
	@Column(name = "support_line_access_mode", nullable = false, length = 32)
	@Builder.Default
	private SupportLineAccessMode supportLineAccessMode = SupportLineAccessMode.HYBRID;

	@JsonIgnore
	@ToString.Exclude
	@Column(name = "jwt_signing_secret", length = 256)
	private String jwtSigningSecret;

	/*
	 * Настройки Jev: пороги уверенности по автоматизациям (key из JevThreshold → значение)
	 * и исключённые приоритеты (по одному на строку).
	 * Наружу отдаются только через JevSettingsDto, в общий JSON настроек не попадают.
	 * Подключение (включение, ключ, URL, модель, таймаут) задаётся только в конфиге.
	 */
	@JsonIgnore
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "jev_thresholds", nullable = false, columnDefinition = "jsonb")
	@Builder.Default
	private Map<String, Double> jevThresholds = new HashMap<>();

	@JsonIgnore
	@Column(name = "jev_reply_required_threshold", nullable = false)
	@Builder.Default
	private Double jevReplyRequiredThreshold = 0.6;

	@JsonIgnore
	@Column(name = "jev_reply_not_required_threshold", nullable = false)
	@Builder.Default
	private Double jevReplyNotRequiredThreshold = 0.25;

	@JsonIgnore
	@Column(name = "jev_priority_excluded", nullable = false, columnDefinition = "text")
	@Builder.Default
	private String jevPriorityExcluded = "Приостановленно";

	/* Детектор массовых инцидентов Jev: окно, порог разных клиентов, порог «это сбой», пауза после срабатывания */
	@JsonIgnore
	@Column(name = "jev_incident_window_minutes", nullable = false)
	@Builder.Default
	private Integer jevIncidentWindowMinutes = 15;

	@JsonIgnore
	@Column(name = "jev_incident_min_clients", nullable = false)
	@Builder.Default
	private Integer jevIncidentMinClients = 3;

	@JsonIgnore
	@Column(name = "jev_incident_outage_threshold", nullable = false)
	@Builder.Default
	private Double jevIncidentOutageThreshold = 0.7;

	@JsonIgnore
	@Column(name = "jev_incident_cooldown_minutes", nullable = false)
	@Builder.Default
	private Integer jevIncidentCooldownMinutes = 60;

	@JsonIgnore
	@Enumerated(EnumType.STRING)
	@Column(name = "jev_incident_mode", nullable = false, length = 16)
	@Builder.Default
	private ru.ravel.ItDesk.model.incident.IncidentDetectorMode jevIncidentMode =
			ru.ravel.ItDesk.model.incident.IncidentDetectorMode.NOTIFY_ONLY;

	/** P1 ставится только если разных клиентов не меньше этого числа, иначе максимум P2 */
	@JsonIgnore
	@Column(name = "jev_incident_p1_min_clients", nullable = false)
	@Builder.Default
	private Integer jevIncidentP1MinClients = 10;


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
