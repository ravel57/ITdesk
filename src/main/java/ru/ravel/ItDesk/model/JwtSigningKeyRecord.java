package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

@Entity
@Table(name = "jwt_signing_key")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JwtSigningKeyRecord {

	@Id
	@Column(name = "key_id", nullable = false, length = 64)
	private String keyId;

	@JsonIgnore
	@ToString.Exclude
	@Column(name = "secret", nullable = false, columnDefinition = "TEXT")
	private String secret;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "retired_at")
	private Instant retiredAt;

	@Column(name = "active", nullable = false)
	private boolean active;
}
