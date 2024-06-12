package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Knowledge implements Comparable<Knowledge> {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String title;

	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	private List<String> texts = new ArrayList<>();

	@ManyToMany(fetch = FetchType.EAGER)
	private List<Tag> tags;

	private Integer orderNumber;

	@Override
	public int compareTo(@NotNull Knowledge o) {
		return orderNumber.compareTo(o.orderNumber);
	}
}
