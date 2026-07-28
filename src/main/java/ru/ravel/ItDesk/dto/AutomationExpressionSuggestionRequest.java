package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;


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

	@Builder.Default
	private Integer limit = 50;
}
