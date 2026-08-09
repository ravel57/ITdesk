package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.*;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class EmailAccount {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String name;

	private String emailFrom;

	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	@ToString.Exclude
	private String password;

	private String smtpServer;

	private Integer smtpPort;

	private String imapServer;

	private Integer imapPort;

	private String subject;
}