package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
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

	// Значение по умолчанию: Hibernate (ddl-auto) добавляет колонку раньше Flyway, без него ALTER не проходит
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	@Builder.Default
	private KnowledgeStatus status = KnowledgeStatus.PUBLISHED;

	/** Заявка, из которой сформирован черновик статьи. */
	private Long sourceTaskId;

	@Override
	public int compareTo(@NotNull Knowledge o) {
		return Comparator.nullsLast(Integer::compareTo).compare(orderNumber, o.orderNumber);
	}


	@JsonIgnore
	public boolean isDraft() {
		return status == KnowledgeStatus.DRAFT;
	}
}
