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
	protected Long id;

	protected String name;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "sla_durations", joinColumns = @JoinColumn(name = "organization_id"))
	@Column(name = "duration")
	@Convert(converter = DurationConverter.class)
	protected Map<Priority, Duration> sla = new HashMap<>();

	protected Integer orderNumber = 1;

	@Override
	public int compareTo(@NotNull Organization o) {
		if (this.orderNumber != null && o.orderNumber != null) {
			return orderNumber.compareTo(o.orderNumber);
		} else  {
			return 0;
		}
	}
}