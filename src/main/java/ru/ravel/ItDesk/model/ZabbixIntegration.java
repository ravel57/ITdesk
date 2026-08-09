package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;

@Entity
@Table(name = "zabbix_integration", uniqueConstraints = {
		@UniqueConstraint(name = "uk_zabbix_integration_name", columnNames = "name")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ZabbixIntegration {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(nullable = false, length = 160)
	private String name;

	@Column(name = "base_url", nullable = false, length = 1024)
	private String baseUrl;

	@Column(length = 255)
	private String username;

	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	@ToString.Exclude
	@Column(length = 2048)
	private String password;

	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	@ToString.Exclude
	@Column(name = "api_token", length = 4096)
	private String apiToken;

	@Column(nullable = false)
	@Builder.Default
	private Boolean active = true;

	@Column(name = "request_timeout_seconds", nullable = false)
	@Builder.Default
	private Integer requestTimeoutSeconds = 15;

	@Column(name = "last_tested_at")
	private ZonedDateTime lastTestedAt;

	@Column(name = "last_test_success")
	private Boolean lastTestSuccess;

	@Column(name = "last_test_message", length = 2048)
	private String lastTestMessage;

	@JsonIgnore
	public boolean hasApiToken() {
		return apiToken != null && !apiToken.isBlank();
	}

	@JsonIgnore
	public boolean hasPassword() {
		return password != null && !password.isBlank();
	}
}
