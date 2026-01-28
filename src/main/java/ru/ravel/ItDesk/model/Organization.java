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
@Builder
@AllArgsConstructor
@NoArgsConstructor
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
	@CollectionTable(name = "organization_sla", joinColumns = @JoinColumn(name = "organization_id"))
	@MapKeyEnumerated(EnumType.STRING)
	@MapKeyColumn(name = "priority")
	private Map<Priority, SlaValue> sla = new HashMap<>();

	@Builder.Default
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