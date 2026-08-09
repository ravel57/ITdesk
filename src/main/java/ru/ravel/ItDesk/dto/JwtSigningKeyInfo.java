package ru.ravel.ItDesk.dto;

import java.time.Instant;

public record JwtSigningKeyInfo(
		String keyId,
		Instant createdAt,
		Instant retiredAt,
		boolean active
) {
}
