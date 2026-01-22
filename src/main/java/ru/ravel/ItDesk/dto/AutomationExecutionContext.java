package ru.ravel.ItDesk.model.automatosation;

import lombok.AllArgsConstructor;
import lombok.Data;
import ru.ravel.ItDesk.model.AutomationTrigger;

@Data
@AllArgsConstructor
public class AutomationExecutionContext {
	private AutomationTrigger trigger;
	private Events event;
}