package ru.ravel.ItDesk.dto;

import lombok.Data;

import java.time.ZonedDateTime;

@Data
public class IncidentTimelineRequest {
	private String type;
	private String audience;
	private String text;
	private String templateKey;
	private ZonedDateTime nextUpdateAt;
}
