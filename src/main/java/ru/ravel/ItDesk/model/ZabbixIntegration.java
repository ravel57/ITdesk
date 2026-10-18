package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;

@Entity
@Table(name = "zabbix_integration", uniqueConstraints = {
		@UniqueConstraint(name = "uk_zabbix_integration_name", columnNames = "name")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ZabbixIntegration {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(nullable = false, length = 160)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	@Builder.Default
	private ZabbixIntegrationMode mode = ZabbixIntegrationMode.API_POLLING;

	/** Адрес Zabbix. В режиме веб-хука необязателен: нужен только для ссылок на события */
	@Column(name = "base_url", length = 1024)
	private String baseUrl;

	@Column(length = 255)
	private String username;

	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	@ToString.Exclude
	@Column(length = 2048)
	private String password;

	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	@ToString.Exclude
	@Column(name = "api_token", length = 4096)
	private String apiToken;

	@Column(nullable = false)
	@Builder.Default
	private Boolean active = true;

	@Column(name = "request_timeout_seconds", nullable = false)
	@Builder.Default
	private Integer requestTimeoutSeconds = 15;

	@Column(name = "last_tested_at")
	private ZonedDateTime lastTestedAt;

	@Column(name = "last_test_success")
	private Boolean lastTestSuccess;

	@Column(name = "last_test_message", length = 2048)
	private String lastTestMessage;

	/* ---- Режим веб-хука ---- */

	/** Часть адреса приёма событий: /api/v1/integrations/zabbix/{webhookKey}/events */
	@Column(name = "webhook_key", length = 64, unique = true)
	private String webhookKey;

	/** SHA-256 токена из заголовка X-ULDesk-Token; сам токен показывается один раз при выпуске */
	@JsonIgnore
	@ToString.Exclude
	@Column(name = "webhook_token_hash", length = 128)
	private String webhookTokenHash;

	@Column(name = "webhook_token_issued_at")
	private ZonedDateTime webhookTokenIssuedAt;

	/** Внешний адрес ULDesk, на который Zabbix отправляет события (для адреса приёма и ссылок) */
	@Column(name = "public_url", length = 1024)
	private String publicUrl;

	/** Часовой пояс дат Zabbix ({EVENT.DATE} {EVENT.TIME}), например Europe/Moscow */
	@Column(name = "event_timezone", length = 64)
	private String eventTimezone;

	/** Адреса и подсети, с которых принимаются события, через запятую. Пусто — с любых */
	@Column(name = "allowed_ips", length = 1024)
	private String allowedIps;

	/** Через сколько минут без проверки связи считать, что связи с Zabbix нет. 0 — не следить */
	@Column(name = "heartbeat_timeout_minutes", nullable = false)
	@Builder.Default
	private Integer heartbeatTimeoutMinutes = 15;

	@Column(name = "last_event_at")
	private ZonedDateTime lastEventAt;

	@Column(name = "last_heartbeat_at")
	private ZonedDateTime lastHeartbeatAt;

	@Column(name = "connection_lost", nullable = false)
	@Builder.Default
	private Boolean connectionLost = false;

	@JsonIgnore
	public boolean isWebhook() {
		return mode == ZabbixIntegrationMode.WEBHOOK;
	}

	@JsonIgnore
	public boolean hasWebhookToken() {
		return webhookTokenHash != null && !webhookTokenHash.isBlank();
	}

	@JsonIgnore
	public boolean hasApiToken() {
		return apiToken != null && !apiToken.isBlank();
	}

	@JsonIgnore
	public boolean hasPassword() {
		return password != null && !password.isBlank();
	}
}
