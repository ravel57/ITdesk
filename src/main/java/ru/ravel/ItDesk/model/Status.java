package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.jetbrains.annotations.NotNull;


@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Status implements Comparable<Status> {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String name;

	private Boolean defaultSelection;

	@Column(nullable = false, columnDefinition = "int default 0")
	private Integer orderNumber;


	@Override
	public int compareTo(@NotNull Status o) {
		return orderNumber.compareTo(o.orderNumber);
	}
}
