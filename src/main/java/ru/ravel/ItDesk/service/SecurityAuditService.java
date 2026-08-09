package ru.ravel.ItDesk.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.JwtTokenInfo;
import ru.ravel.ItDesk.dto.JwtTokenResponse;

@Service
public class SecurityAuditService {

	private static final Logger AUDIT = LoggerFactory.getLogger("SECURITY_AUDIT");

	public void jwtIssued(JwtTokenResponse token, HttpServletRequest request) {
		AUDIT.info(
				"event=jwt_issued tokenId={} jti={} name=\"{}\" user={} scopes=\"{}\" ip={} userAgent=\"{}\"",
				token.tokenId(), safe(token.jti()), safe(token.name()), safe(token.username()),
				safe(String.join(" ", token.scopes())), clientIp(request), userAgent(request)
		);
	}

	public void jwtRevoked(JwtTokenInfo token, String actor, HttpServletRequest request) {
		AUDIT.info(
				"event=jwt_revoked tokenId={} jti={} name=\"{}\" actor={} ip={} userAgent=\"{}\"",
				token.id(), safe(token.jti()), safe(token.name()), safe(actor), clientIp(request), userAgent(request)
		);
	}

	public void jwtRevokedAll(int count, String actor, HttpServletRequest request) {
		AUDIT.info(
				"event=jwt_revoke_all count={} actor={} ip={} userAgent=\"{}\"",
				count, safe(actor), clientIp(request), userAgent(request)
		);
	}

	public void jwtUsed(JwtAuthenticationResult result, HttpServletRequest request) {
		AUDIT.info(
				"event=jwt_used tokenId={} jti={} name=\"{}\" user={} method={} endpoint=\"{}\" ip={} userAgent=\"{}\"",
				result.tokenId(), safe(result.jti()), safe(result.tokenName()), safe(result.user().getUsername()),
				safe(request.getMethod()), endpoint(request), clientIp(request), userAgent(request)
		);
	}

	public void jwtRejected(String reason, HttpServletRequest request) {
		AUDIT.warn(
				"event=jwt_rejected reason=\"{}\" method={} endpoint=\"{}\" ip={} userAgent=\"{}\"",
				safe(reason), safe(request.getMethod()), endpoint(request), clientIp(request), userAgent(request)
		);
	}

	public void jwtSigningKeyRotated(String keyId, String actor, HttpServletRequest request) {
		AUDIT.info(
				"event=jwt_signing_key_rotated keyId={} actor={} ip={} userAgent=\"{}\"",
				safe(keyId), safe(actor), clientIp(request), userAgent(request)
		);
	}

	public String clientIp(HttpServletRequest request) {
		return safeLimit(request == null ? null : request.getRemoteAddr(), 64);
	}

	public String userAgent(HttpServletRequest request) {
		return safeLimit(request == null ? null : request.getHeader("User-Agent"), 512);
	}

	public String endpoint(HttpServletRequest request) {
		if (request == null) {
			return "-";
		}
		return safeLimit(request.getRequestURI(), 512);
	}

	private String safe(String value) {
		return safeLimit(value, 512);
	}

	private String safeLimit(String value, int maxLength) {
		String normalized = value == null ? "-" : value.replace('\r', ' ').replace('\n', ' ').trim();
		if (normalized.isBlank()) {
			return "-";
		}
		return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
	}
}
