package ru.ravel.ItDesk.model;

import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.GrantedAuthority;

import java.util.Arrays;

@Getter
public enum Role implements GrantedAuthority {
	ADMIN("Администратор", true),
	MANAGER("Менеджер поддержки", true),
	OPERATOR("Оператор поддержки", true),
	OBSERVER("Менеджер организации", false),
	CLIENT("Клиент", false),
	;

	private final String name;
	private final boolean consumesLicense;

	Role(String name, boolean consumesLicense) {
		this.name = name;
		this.consumesLicense = consumesLicense;
	}

	public boolean consumesLicense() {
		return consumesLicense;
	}

	public static Role getByName(String name) {
		return Arrays.stream(values())
				.filter(role -> role.getName().equals(name))
				.findFirst()
				.orElseThrow();
	}

	@NotNull
	@Contract(pure = true)
	@Override
	public String getAuthority() {
		return "ROLE_%s".formatted(name());
	}
}
