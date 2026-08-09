package ru.ravel.ItDesk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LlmRetrainResponse {
	private String status;

	@JsonProperty("instance_name")
	private String instanceName;

	private Object wipe;
	private Integer documents;
	private Integer chunks;
	private Integer count;
}
