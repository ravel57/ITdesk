package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.ravel.ItDesk.model.UserNotificationEvent;

import java.time.ZonedDateTime;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserNotificationHistoryDto {
	private Long id;
	private UserNotificationEvent event;
	private String title;
	private String body;
	private String message;
	private Long userId;
	private ZonedDateTime createdAt;
	private ZonedDateTime readAt;
	private Boolean read;
}
