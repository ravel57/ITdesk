package ru.ravel.ItDesk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LlmKbCountResponse {
	@JsonProperty("instance_name")
	private String instanceName;

	private Integer count;
}
