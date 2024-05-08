package ru.ravel.ItDesk.model;

import lombok.Data;

@Data
public class FrontendUser {
	private String firstname;

	private String lastname;

	private String username;

	private String password;

	private String authorities;
}
