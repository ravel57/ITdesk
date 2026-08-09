package ru.ravel.ItDesk.dto;

import java.util.List;

public record ClientPortalPage(
		List<ClientPortalMessageDto> messages,
		boolean isEnd
) {
}
