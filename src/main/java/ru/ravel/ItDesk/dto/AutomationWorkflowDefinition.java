package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AutomationWorkflowDefinition {

	@Builder.Default
	private Integer version = 2;

	private String entryNodeId;

	@Builder.Default
	private List<Node> nodes = new ArrayList<>();

	@Builder.Default
	private List<Edge> edges = new ArrayList<>();


	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	@Builder
	public static class Node {
		private String id;
		private String type;
		private String label;
		private Double x;
		private Double y;

		@Builder.Default
		private Map<String, Object> config = new LinkedHashMap<>();
	}


	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	@Builder
	public static class Edge {
		private String id;
		private String source;
		private String target;
		private String sourceHandle;
	}
}
