package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;


/**
 * Запись журнала приёма веб-хуков Zabbix: что пришло и чем закончилось.
 */
@Entity
@Table(name = "monitoring_webhook_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MonitoringWebhookLog {

	public enum Kind {EVENT, HEARTBEAT}

	public enum Outcome {
		/** Событие принято и привязано к сервису */
		ACCEPTED,
		/** Повтор уже принятого события */
		DUPLICATE,
		/** Принято, но не нашлось сервиса для привязки */
		UNMAPPED,
		/** Отклонено: токен, адрес, контракт */
		REJECTED,
		/** Zabbix молчит дольше таймаута: ни событий, ни проверок связи */
		CONNECTION_LOST,
		/** Zabbix снова вышел на связь */
		CONNECTION_RESTORED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "integration_id", nullable = false)
	private Long integrationId;

	@Column(name = "received_at", nullable = false)
	private ZonedDateTime receivedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Kind kind;

	@Column(name = "event_id", length = 64)
	private String eventId;

	@Column(name = "event_status", length = 16)
	private String eventStatus;

	@Column(name = "event_name", length = 1024)
	private String eventName;

	private Integer severity;

	@Column(length = 255)
	private String host;

	@Column(name = "service_id")
	private Long serviceId;

	@Column(name = "service_code", length = 80)
	private String serviceCode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private Outcome outcome;

	@Column(length = 1024)
	private String message;

	@Column(name = "remote_ip", length = 64)
	private String remoteIp;
}
