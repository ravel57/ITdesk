package ru.ravel.ItDesk.model;

import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.dto.TriggerTypeOption;
import ru.ravel.ItDesk.model.automatosation.TriggerType;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TriggerTypeLabelTest {

	@Test
	void everyEventHasRussianLabelAndGroup() {
		for (TriggerType type : TriggerType.values()) {
			assertFalse(type.label() == null || type.label().isBlank(), type.name());
			assertFalse(type.group() == null || type.group().isBlank(), type.name());
			assertTrue(type.label().chars().anyMatch(ch -> Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC),
					type.name() + " → " + type.label());
		}
		assertEquals(TriggerType.values().length,
				Arrays.stream(TriggerType.values()).map(TriggerType::label).distinct().count(), "названия не повторяются");
	}

	@Test
	void optionCarriesCodeLabelAndGroup() {
		assertEquals(new TriggerTypeOption("TASK_CREATED", "Заявка создана", "Заявки"), TriggerTypeOption.of(TriggerType.TASK_CREATED));
	}
}
