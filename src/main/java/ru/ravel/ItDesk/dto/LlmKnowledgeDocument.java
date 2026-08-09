package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmKnowledgeDocument {
	private String source;
	private String text;
	private String tags;
}
