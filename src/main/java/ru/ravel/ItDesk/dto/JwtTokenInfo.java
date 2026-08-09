package ru.ravel.ItDesk.dto;

import java.time.Instant;
import java.util.List;

public record JwtTokenInfo(
		Long id,
		String jti,
		String name,
		Long userId,
		String username,
		List<String> scopes,
		String signingKeyId,
		Instant issuedAt,
		Instant expiresAt,
		Instant revokedAt,
		Long revokedByUserId,
		Instant lastUsedAt,
		String lastUsedIp,
		String lastUserAgent,
		String lastEndpoint,
		long useCount,
		boolean active
) {
}
