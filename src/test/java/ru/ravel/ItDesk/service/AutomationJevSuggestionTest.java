package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.dto.AutomationExpressionSuggestionRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AutomationJevSuggestionTest {

	private final AutomationExpressionMetadataService service = new AutomationExpressionMetadataService(new ObjectMapper());

	@Test
	void suggestsJevActions() {
		String labels = labels(suggest("jev."));
		assertTrue(labels.contains("classifyTask"), labels);
		assertTrue(labels.contains("setPriority"), labels);
		assertTrue(labels.contains("setAnswerRequiredIfNeeded"), labels);
		assertTrue(!labels.contains("ready") && !labels.contains("ruleName") && !labels.contains("taskText"), labels);
	}

	@Test
	void suggestsThresholdOverloadsWithParameterNames() {
		String all = suggest("jev.").toString();
		assertTrue(all.contains("createTaskIfNeeded(BigDecimal threshold, BigDecimal splitThreshold)"), all);
		assertTrue(all.contains("escalateIfNeeded(BigDecimal threshold)"), all);
	}

	@Test
	@SuppressWarnings("unchecked")
	void signatureHelpShowsOverloadsAndCurrentArgument() {
		Map<String, Object> help = signatureHelp("jev.createTaskIfNeeded(0.7, ");
		assertEquals("jev.createTaskIfNeeded", help.get("method"));
		assertEquals(1, help.get("activeParameter"));
		List<Map<String, Object>> signatures = (List<Map<String, Object>>) help.get("signatures");
		assertEquals(List.of(0, 1, 2), signatures.stream().map(item -> ((List<?>) item.get("parameters")).size()).toList());
		assertEquals(Map.of("type", "BigDecimal", "name", "splitThreshold"),
				((List<?>) signatures.get(2).get("parameters")).get(1));

		// запятая в строке и вложенный вызов не сбивают номер аргумента и метод
		assertEquals(1, signatureHelp("client.sendMessage('a, b' + task.name.trim(), ").get("activeParameter"));
		assertEquals("jev.escalateIfNeeded", signatureHelp("jev.escalateIfNeeded(task.name.trim(").get("method"));
		assertNull(signatureHelp("jev.escalateIfNeeded(0.8)"));
	}

	@Test
	void suggestsJevRoot() {
		assertTrue(labels(suggest("je")).contains("jev"));
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> suggest(String text) {
		AutomationExpressionSuggestionRequest request = new AutomationExpressionSuggestionRequest();
		request.setText(text);
		request.setCursor(text.length());
		request.setMode("ACTION");
		return (List<Map<String, Object>>) service.suggest(request).get("suggestions");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> signatureHelp(String text) {
		AutomationExpressionSuggestionRequest request = new AutomationExpressionSuggestionRequest();
		request.setText(text);
		request.setCursor(text.length());
		request.setMode("ACTION");
		return (Map<String, Object>) service.suggest(request).get("signatureHelp");
	}

	private String labels(List<Map<String, Object>> suggestions) {
		return suggestions.stream().map(item -> item.get("label") + "|" + item.get("insertText")).toList().toString();
	}
}
