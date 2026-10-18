package ru.ravel.ItDesk.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.ravel.ItDesk.dto.JwtTokenInfo;
import ru.ravel.ItDesk.dto.JwtTokenResponse;
import ru.ravel.ItDesk.model.SecurityAuditEvent;
import ru.ravel.ItDesk.model.SecurityAuditEventType;
import ru.ravel.ItDesk.repository.SecurityAuditEventRepository;

import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;


/**
 * Журнал безопасности: каждое событие пишется в лог SECURITY_AUDIT и в таблицу security_audit_event
 * (страница «Журнал безопасности»). Запись в БД идёт в отдельной транзакции: откат основной операции
 * или сбой журнала не теряют и не ломают друг друга.
 */
@Service
public class SecurityAuditService {

	private static final Logger AUDIT = LoggerFactory.getLogger("SECURITY_AUDIT");
	private static final int RETENTION_DAYS = 365;

	private final SecurityAuditEventRepository repository;
	private final TransactionTemplate newTransaction;


	public SecurityAuditService(SecurityAuditEventRepository repository, PlatformTransactionManager transactionManager) {
		this.repository = repository;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}


	/** Событие от имени текущего пользователя; IP и браузер берутся из текущего запроса. */
	public void record(SecurityAuditEventType type, String target, String details) {
		HttpServletRequest request = currentRequest();
		record(type, currentActor(), target, details, request);
	}


	public void record(SecurityAuditEventType type, String actor, String target, String details, HttpServletRequest request) {
		String ip = clientIp(request);
		String userAgent = userAgent(request);
		String message = "event={} actor={} target=\"{}\" details=\"{}\" ip={} userAgent=\"{}\"";
		Object[] args = {type.name().toLowerCase(), safeLimit(actor, 254), safe(target), safe(details), ip, userAgent};
		if (type.isAttention()) {
			AUDIT.warn(message, args);
		} else {
			AUDIT.info(message, args);
		}
		try {
			newTransaction.executeWithoutResult(status -> repository.save(SecurityAuditEvent.builder()
					.createdAt(ZonedDateTime.now())
					.type(type)
					.actor(nullIfDash(safeLimit(actor, 254)))
					.target(nullIfDash(safeLimit(target, 512)))
					.details(details == null || details.isBlank() ? null : details.trim())
					.ip(nullIfDash(ip))
					.userAgent(nullIfDash(userAgent))
					.build()));
		} catch (RuntimeException e) {
			AUDIT.error("event=audit_persist_failed type={}", type, e);
		}
	}


	@Scheduled(fixedDelay = 24, timeUnit = TimeUnit.HOURS, initialDelay = 1)
	void cleanup() {
		try {
			newTransaction.executeWithoutResult(status ->
					repository.deleteOlderThan(ZonedDateTime.now().minusDays(RETENTION_DAYS)));
		} catch (RuntimeException e) {
			AUDIT.error("event=audit_cleanup_failed", e);
		}
	}


	public String currentActor() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken
				? null
				: authentication.getName();
	}


	private static HttpServletRequest currentRequest() {
		return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
				? attributes.getRequest()
				: null;
	}


	private static String nullIfDash(String value) {
		return "-".equals(value) ? null : value;
	}

	public void loginSucceeded(String username, HttpServletRequest request) {
		record(SecurityAuditEventType.LOGIN_SUCCEEDED, username, username, null, request);
	}

	/** reason — тип исключения Spring Security: BadCredentials (неверный логин или пароль), Disabled, Locked… */
	public void loginFailed(String username, String reason, HttpServletRequest request) {
		record(SecurityAuditEventType.LOGIN_FAILED, username, username, "Причина: " + safe(reason), request);
	}

	public void passwordChangedByResetLink(String username) {
		record(SecurityAuditEventType.PASSWORD_CHANGED_BY_RESET_LINK, username, username, null, currentRequest());
	}

	public void jwtIssued(JwtTokenResponse token, HttpServletRequest request) {
		record(SecurityAuditEventType.JWT_ISSUED, safe(token.username()),
				"Ключ #" + token.tokenId() + " «" + safe(token.name()) + "»",
				"Права: " + String.join(", ", token.scopes()) + "; jti=" + safe(token.jti()), request);
	}

	public void jwtRevoked(JwtTokenInfo token, String actor, HttpServletRequest request) {
		record(SecurityAuditEventType.JWT_REVOKED, actor, "Ключ #" + token.id() + " «" + safe(token.name()) + "»",
				"jti=" + safe(token.jti()), request);
	}

	public void jwtRevokedAll(int count, String actor, HttpServletRequest request) {
		record(SecurityAuditEventType.JWT_REVOKED_ALL, actor, null, "Отозвано ключей: " + count, request);
	}

	public void jwtUsed(JwtAuthenticationResult result, HttpServletRequest request) {
		AUDIT.info(
				"event=jwt_used tokenId={} jti={} name=\"{}\" user={} method={} endpoint=\"{}\" ip={} userAgent=\"{}\"",
				result.tokenId(), safe(result.jti()), safe(result.tokenName()), safe(result.user().getUsername()),
				safe(request.getMethod()), endpoint(request), clientIp(request), userAgent(request)
		);
	}

	public void jwtRejected(String reason, HttpServletRequest request) {
		record(SecurityAuditEventType.JWT_REJECTED, null, safe(request.getMethod()) + " " + endpoint(request),
				"Причина: " + safe(reason), request);
	}

	public void jwtSigningKeyRotated(String keyId, String actor, HttpServletRequest request) {
		record(SecurityAuditEventType.JWT_SIGNING_KEY_ROTATED, actor, "Ключ подписи " + safe(keyId), null, request);
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
