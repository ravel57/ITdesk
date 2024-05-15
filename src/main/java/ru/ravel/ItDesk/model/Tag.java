package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Tag implements Comparable<Tag> {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String name;

	private String description;

	@Column(nullable = false, columnDefinition = "int default 0")
	private Integer orderNumber;


	@Override
	public int compareTo(@NotNull Tag o) {
		return orderNumber.compareTo(o.orderNumber);
	}

}
