package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import lombok.ToString;

import java.time.Instant;


/**
 * Значение секрета плагина, заданное администратором инсталляции.
 * <p>
 * Хранится зашифрованным; наружу через API не отдаётся никогда — доступен
 * только факт «задан / не задан». Сам плагин значения тоже не видит: секрет
 * подставляет брокер в исходящий запрос.
 */
@Entity
@Table(
		name = "plugin_secret",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_plugin_secret",
				columnNames = {"plugin_key", "name"}
		)
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginSecretValue {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "plugin_key", nullable = false, length = 128)
	private String pluginKey;

	@Column(name = "name", nullable = false, length = 64)
	private String name;

	@JsonIgnore
	@ToString.Exclude
	@Column(name = "value_encrypted", nullable = false, columnDefinition = "TEXT")
	private String valueEncrypted;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "updated_by", length = 128)
	private String updatedBy;
}
