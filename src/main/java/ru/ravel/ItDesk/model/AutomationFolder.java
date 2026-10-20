package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;


/**
 * Папка автоматизаций. Хранит только название, «свёрнуто», родителя и место среди соседей;
 * что в ней лежит, определяет {@link AutomationTrigger#getFolderId()}.
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AutomationFolder {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, columnDefinition = "boolean default false")
	@Builder.Default
	private Boolean collapsed = false;

	@Column(nullable = false, columnDefinition = "int default 0")
	@Builder.Default
	private Integer displayOrder = 0;

	/** Родительская папка; null — верхний уровень. Вложенность произвольной глубины. */
	private Long parentId;
}
