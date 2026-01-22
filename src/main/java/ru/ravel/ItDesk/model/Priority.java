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
public class Priority implements Comparable<Priority> {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String name;

	private Boolean defaultSelection;

	@Column(nullable = false, columnDefinition = "int default 0")
	private Integer orderNumber;

	private Boolean critical;

	@Override
	public int compareTo(@NotNull Priority o) {
		return orderNumber.compareTo(o.orderNumber);
	}
}
