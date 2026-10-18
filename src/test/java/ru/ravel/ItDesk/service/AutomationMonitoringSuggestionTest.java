package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.dto.AutomationExpressionSuggestionRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationMonitoringSuggestionTest {

	private final AutomationExpressionMetadataService service = new AutomationExpressionMetadataService(new ObjectMapper());

	@Test
	@SuppressWarnings("unchecked")
	void suggestsIncidentFromMonitoring() {
		for (String text : List.of("incident.", "inc")) {
			AutomationExpressionSuggestionRequest request = new AutomationExpressionSuggestionRequest();
			request.setText(text);
			request.setCursor(text.length());
			request.setMode("ACTION");
			List<Map<String, Object>> suggestions = (List<Map<String, Object>>) service.suggest(request).get("suggestions");
			String labels = suggestions.stream().map(item -> item.get("label") + "|" + item.get("insertText")).toList().toString();
			assertTrue(labels.contains(text.endsWith(".") ? "fromMonitoring" : "incident"), labels);
		}
	}
}
