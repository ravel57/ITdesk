package ru.ravel.ItDesk.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserNotification {
	private UserNotificationEvent event;
	private String message;
	private Long userId;
}