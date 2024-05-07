package ru.ravel.ItDesk.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TaskFilter {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	String label;

	@JdbcTypeCode(SqlTypes.JSON)
	List<Filter> selectedOptions;

	@Data
	@AllArgsConstructor
	public static class Filter {
		String label;
		List<String> selectedOptions;
	}
}
