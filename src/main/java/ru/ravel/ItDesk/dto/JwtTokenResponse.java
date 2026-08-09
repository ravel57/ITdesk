package ru.ravel.ItDesk.dto;

import java.time.Instant;
import java.util.List;

public record JwtTokenResponse(
		Long tokenId,
		String jti,
		String name,
		String token,
		String tokenType,
		Instant issuedAt,
		Instant expiresAt,
		String username,
		List<String> authorities,
		List<String> scopes,
		String signingKeyId
) {
}
