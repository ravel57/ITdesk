package ru.ravel.ItDesk.model;

import lombok.Getter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.GrantedAuthority;

import java.util.Arrays;

@Getter
public enum Role implements GrantedAuthority {
	ADMIN("Администратор"),
//	MANAGER("Менеджер поддержки"),
	OPERATOR("Оператор поддержки")/*,
	OBSERVER("Менеджер организации"),
	CLIENT("Клиент")*/;

	private final String name;

	Role(String name) {
		this.name = name;
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
