package ru.ravel.ItDesk.service;

import ru.ravel.ItDesk.model.User;

import java.util.List;

public record JwtAuthenticationResult(
		User user,
		Long tokenId,
		String jti,
		String tokenName,
		List<String> scopes
) {
}
