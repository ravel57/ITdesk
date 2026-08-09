package ru.ravel.ItDesk.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(
		name = "jwt_token",
		indexes = {
				@Index(name = "idx_jwt_token_user_issued", columnList = "user_id, issued_at"),
				@Index(name = "idx_jwt_token_active", columnList = "revoked_at, expires_at")
		}
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JwtTokenRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "jti", nullable = false, unique = true, length = 64)
	private String jti;

	@Column(name = "name", nullable = false, length = 120)
	private String name;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "username", nullable = false, length = 255)
	private String username;

	@Column(name = "scopes", nullable = false, length = 1024)
	private String scopes;

	@Column(name = "signing_key_id", length = 64)
	private String signingKeyId;

	@Column(name = "issued_at", nullable = false)
	private Instant issuedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_by_user_id")
	private Long revokedByUserId;

	@Column(name = "last_used_at")
	private Instant lastUsedAt;

	@Column(name = "last_used_ip", length = 64)
	private String lastUsedIp;

	@Column(name = "last_user_agent", length = 512)
	private String lastUserAgent;

	@Column(name = "last_endpoint", length = 512)
	private String lastEndpoint;

	@Column(name = "use_count", nullable = false)
	@Builder.Default
	private long useCount = 0L;
}
