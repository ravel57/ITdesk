package ru.ravel.ItDesk.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;


/**
 * Право, которое плагин запросил в манифесте.
 * <p>
 * Установка плагина ничего не разрешает: строка появляется здесь со статусом
 * approved = false и начинает действовать только после явного одобрения
 * администратором инсталляции.
 */
@Entity
@Table(
		name = "plugin_grant",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_plugin_grant",
				columnNames = {"plugin_key", "permission"}
		)
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginGrant {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "plugin_key", nullable = false, length = 128)
	private String pluginKey;

	/** Канонизированная строка права: http:get:https://host/path/ либо secret:name. */
	@Column(name = "permission", nullable = false, length = 1024)
	private String permission;

	@Column(name = "approved", nullable = false)
	private boolean approved;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	@Column(name = "approved_at")
	private Instant approvedAt;

	@Column(name = "approved_by", length = 128)
	private String approvedBy;
}
