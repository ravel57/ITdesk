package ru.ravel.ItDesk.dto;

import lombok.Data;

@Data
public class ZabbixIntegrationRequest {
	private String name;
	private String baseUrl;
	private String username;
	private String password;
	private String apiToken;
	private Boolean active;
	private Integer requestTimeoutSeconds;
}
