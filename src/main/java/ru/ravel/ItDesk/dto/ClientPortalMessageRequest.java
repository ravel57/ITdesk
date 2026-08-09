package ru.ravel.ItDesk.dto;

import lombok.Data;

@Data
public class ClientPortalMessageRequest {
	private String text;
	private String fileName;
	private String fileType;
	private String fileUuid;
	private Long replyMessageId;
}
