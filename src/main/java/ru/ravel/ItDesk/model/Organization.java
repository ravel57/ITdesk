package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.jetbrains.annotations.NotNull;
import ru.ravel.ItDesk.dto.DurationConverter;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;


@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Organization implements Comparable<Organization>, Serializable {
	@Serial
	private static final long serialVersionUID = 46782534215464L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String name;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "sla_durations_by_priority", joinColumns = @JoinColumn(name = "organization_id"))
	@Column(name = "duration")
	@Convert(converter = DurationConverter.class)
	private Map<Priority, Duration> slaByPriority = new HashMap<>();

	@Column(nullable = false, columnDefinition = "int default 0")
	private Integer orderNumber;

	@Override
	public int compareTo(@NotNull Organization o) {
		return orderNumber.compareTo(o.orderNumber);
	}
}
