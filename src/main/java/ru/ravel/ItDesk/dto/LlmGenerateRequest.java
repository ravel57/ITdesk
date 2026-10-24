package ru.ravel.ItDesk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;


/** Задание для свободной генерации текста (POST /generate сервиса LLM), без поиска по базе знаний. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmGenerateRequest(
		String prompt,
		@JsonProperty("instance_name") String instanceName
) {
}
