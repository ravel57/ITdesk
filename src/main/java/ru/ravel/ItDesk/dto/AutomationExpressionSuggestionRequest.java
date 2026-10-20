package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationExpressionSuggestionRequest {
	private String text;
	private Integer cursor;

	/** EXPRESSION or ACTION. */
	@Builder.Default
	private String mode = "EXPRESSION";

	@Builder.Default
	private List<String> variables = new ArrayList<>();

	/** Inferred structural schemas; no Java class names are accepted from the client. */
	@Builder.Default
	private Map<String, Object> variableTypes = new LinkedHashMap<>();

	@Builder.Default
	private Integer limit = 50;
}
