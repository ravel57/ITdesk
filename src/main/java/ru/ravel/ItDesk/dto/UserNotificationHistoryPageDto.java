package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserNotificationHistoryPageDto {
	private List<UserNotificationHistoryDto> notifications;
	private long totalElements;
	private int totalPages;
	private int page;
	private int size;
	private long unreadCount;
	private boolean end;
}
