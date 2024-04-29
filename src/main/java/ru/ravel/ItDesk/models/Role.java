package ru.ravel.ItDesk.models;

import jakarta.persistence.*;
import org.springframework.security.core.GrantedAuthority;

//@Entity
//@Table(name = "t_role")
public enum Role implements GrantedAuthority {
	ADMIN,
	OBSERVER;

//	@Id
//	@GeneratedValue(strategy = GenerationType.IDENTITY)
//	private Long id;

	@Override
	public String getAuthority() {
		return name();
	}
}
