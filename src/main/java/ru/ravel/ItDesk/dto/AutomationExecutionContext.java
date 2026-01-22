package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.model.Event;

@Data
@AllArgsConstructor
public class AutomationExecutionContext {
	private AutomationTrigger trigger;
	private Event event;
}