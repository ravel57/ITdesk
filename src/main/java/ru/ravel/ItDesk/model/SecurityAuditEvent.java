package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;


@Entity
@Table(name = "security_audit_event")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityAuditEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "created_at", nullable = false)
	private ZonedDateTime createdAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 64)
	private SecurityAuditEventType type;

	/** Кто совершил действие (логин); для входа — введённый логин. */
	@Column(length = 254)
	private String actor;

	/** Над чем: пользователь, клиент, сообщение, ключ API. */
	@Column(length = 512)
	private String target;

	@Column(columnDefinition = "text")
	private String details;

	@Column(length = 64)
	private String ip;

	@Column(name = "user_agent", length = 512)
	private String userAgent;
}
