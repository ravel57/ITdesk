package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;


@Data
@AllArgsConstructor
@Builder
public class LlmBody {
	private String query;
	private Integer limit;
	Float alpha;
	Float max_distance;
	Integer min_overlap;
	Boolean include_debug;
}
