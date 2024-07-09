package ru.ravel.ItDesk.dto;

import lombok.Data;

import java.util.List;

@Data
public class UserDto {
	private Long id;

	private String firstname;

	private String lastname;

	private String username;

	private String password;

	private String authorities;

	private List<String> availableOrganizations;
}
