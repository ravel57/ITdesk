package ru.ravel.ItDesk.model.incident;

import jakarta.persistence.*;
import lombok.*;

/**
 * Шаблон текста обновления инцидента, который подставляется в запись таймлайна.
 */
@Entity
@Table(name = "incident_update_template")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class IncidentUpdateTemplate {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	/**
	 * Стабильный ключ шаблона: сохраняется в записи таймлайна (template_key).
	 */
	@Column(name = "template_key", nullable = false, unique = true, length = 64)
	private String key;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String text;

	@Column(nullable = false, columnDefinition = "int default 0")
	@Builder.Default
	private Integer orderNumber = 0;
}
