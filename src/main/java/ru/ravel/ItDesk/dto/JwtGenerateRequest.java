package ru.ravel.ItDesk.dto;

import java.util.List;

public record JwtGenerateRequest(
		String name,
		Integer expiresInHours,
		List<String> scopes
) {
}
