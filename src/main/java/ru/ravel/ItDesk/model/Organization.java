package ru.ravel.ItDesk.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;


@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Organization implements Comparable<Organization> {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	@Override
	public int compareTo(@NotNull Organization o) {
		return this.name.compareTo(o.name);
	}
}
