package ru.ravel.ItDesk.dto;

import lombok.Data;

import java.util.List;


@Data
public class LlmResponse {
	private String answer;
	List<SearchResult> context;
	private Boolean has_kb_answer;
}
