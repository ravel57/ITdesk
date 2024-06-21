package ru.ravel.ItDesk.model;

import lombok.Data;

import java.util.List;

@Data
public class FrontendUser {
	private Long id;

	private String firstname;

	private String lastname;

	private String username;

	private String password;

	private String authorities;

	private List<String> availableOrganizations;
}
