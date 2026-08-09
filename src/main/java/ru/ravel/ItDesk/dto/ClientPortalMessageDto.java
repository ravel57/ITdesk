package ru.ravel.ItDesk.dto;

import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.User;

import java.time.ZonedDateTime;
import java.util.Objects;

public record ClientPortalMessageDto(
		Long id,
		String text,
		ZonedDateTime date,
		ZonedDateTime editedAt,
		boolean fromClient,
		String senderName,
		String fileName,
		String fileType,
		String fileUuid,
		Long replyMessageId
) {
	public static ClientPortalMessageDto from(Message message, String clientName) {
		boolean fromClient = !Boolean.TRUE.equals(message.getIsSent());
		return new ClientPortalMessageDto(
				message.getId(),
				Objects.toString(message.getText(), ""),
				message.getDate(),
				message.getEditedAt(),
				fromClient,
				fromClient ? clientName : getUserDisplayName(message.getUser()),
				message.getFileName(),
				message.getFileType(),
				message.getFileUuid(),
				message.getReplyMessageId()
		);
	}

	private static String getUserDisplayName(User user) {
		if (user == null) {
			return "Поддержка";
		}
		String name = ("%s %s".formatted(Objects.toString(user.getFirstname(), ""), Objects.toString(user.getLastname(), ""))).trim();
		return name.isBlank() ? "Поддержка" : name;
	}
}
