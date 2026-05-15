package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserNotificationSettingsDto {
	private Boolean notifyChatPing;
	private Boolean notifyTaskChatPing;
	private Boolean notifyNewAssignedTask;
	private Boolean notifyTaskNewMessageAssigned;
	private Boolean notifySlaHalfTimePassed;
	private Boolean notifySlaOverdue;
	private Boolean notifyChatUnansweredTooLong;
	private Integer notifyChatUnansweredTooLongMinutes;
	private Boolean notifyDeadlineOverdueBeforeMinutesEnabled;
	private Integer notifyDeadlineOverdueBeforeMinutes;
	private Boolean notifyDeadlineOverdue;
}