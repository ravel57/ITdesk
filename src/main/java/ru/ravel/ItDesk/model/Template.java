package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.jetbrains.annotations.NotNull;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Template implements Comparable<Template> {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String text;

	private String shortcut;

	@Column(nullable = false, columnDefinition = "int default 0")
	private Integer orderNumber;


	@Override
	public int compareTo(@NotNull Template o) {
		return orderNumber.compareTo(o.orderNumber);
	}
}
