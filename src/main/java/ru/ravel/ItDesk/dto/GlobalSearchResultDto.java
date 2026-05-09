package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalSearchResultDto {

	private String id;

	private String entityType;

	private Long entityId;

	private Long clientId;

	private Long taskId;

	private String title;

	private String text;

	private String subtitle;

	private String url;

	private Double score;

	private Map<String, Object> meta;
}