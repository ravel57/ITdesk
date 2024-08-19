package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.jetbrains.annotations.NotNull;


@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type", discriminatorType = DiscriminatorType.STRING)
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Status implements Comparable<Status> {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	protected Long id;

	protected String name;

	protected Boolean defaultSelection;

	@Column(nullable = false, columnDefinition = "int default 0")
	protected Integer orderNumber;


	@Override
	public int compareTo(@NotNull Status o) {
		if (o.getOrderNumber() == null) {
			return 1;
		}
		return getOrderNumber().compareTo(o.getOrderNumber());
	}
}