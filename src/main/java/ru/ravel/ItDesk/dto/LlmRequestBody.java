package ru.ravel.ItDesk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;


@Data
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmRequestBody {

	private String query;

	private Integer limit;

	private Float alpha;

	@JsonProperty("max_distance")
	private Float maxDistance;

	@JsonProperty("min_overlap")
	private Integer minOverlap;

	@JsonProperty("include_debug")
	private Boolean includeDebug;
}
