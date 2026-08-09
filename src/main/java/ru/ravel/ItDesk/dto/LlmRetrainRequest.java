package ru.ravel.ItDesk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRetrainRequest {
	@JsonProperty("instance_name")
	private String instanceName;

	private List<LlmKnowledgeDocument> documents;
}
