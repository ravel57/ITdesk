package ru.ravel.ItDesk.dto;

public record ClientPortalProfileDto(
		Long userId,
		String username,
		String firstname,
		String lastname,
		Long clientId,
		String clientName,
		Long organizationId,
		String organizationName
) {
}
