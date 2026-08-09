package ru.ravel.ItDesk.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TaskFilter {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	Long id;

	String label;

	String filterJoinOperator;

	@Builder.Default
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	TaskFilterScope scope = TaskFilterScope.GLOBAL;

	Long ownerUserId;

	@JdbcTypeCode(SqlTypes.JSON)
	List<Filter> selectedOptions;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Filter {
		String label;
		List<String> selectedOptions;
	}
}