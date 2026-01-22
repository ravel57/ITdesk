package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.jetbrains.annotations.NotNull;
import ru.ravel.ItDesk.model.automatosation.AutomationRuleStatus;
import ru.ravel.ItDesk.model.automatosation.TriggerType;


@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AutomationTrigger implements Comparable<AutomationTrigger> {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String name;

	private String description;

	@Enumerated(EnumType.STRING)
	private TriggerType triggerType;

	private String expression;

	private String action;

	@Enumerated(EnumType.STRING)
	@Builder.Default
	private AutomationRuleStatus automationRuleStatus = AutomationRuleStatus.ENABLED;

//	@OneToMany(fetch = FetchType.EAGER)
//	private List<TriggerExpressionAction> actions;

	@Column(nullable = false, columnDefinition = "int default 0")
	private Integer orderNumber;


	@Override
	public int compareTo(@NotNull AutomationTrigger o) {
		return orderNumber.compareTo(o.orderNumber);
	}
}