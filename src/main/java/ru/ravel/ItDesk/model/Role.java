package ru.ravel.ItDesk.model;

import org.springframework.security.core.GrantedAuthority;


public enum Role implements GrantedAuthority {
	ADMIN,
	OPERATOR,
	OBSERVER;

	@Override
	public String getAuthority() {
		return name();
	}
}
