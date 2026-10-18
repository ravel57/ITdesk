package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.ObserverFeature;
import ru.ravel.ItDesk.model.Permission;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.RolePermissions;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Права текущего пользователя. В контроллерах: {@code @PreAuthorize("@access.can('TASK_EDIT')")}.
 * Права выводятся из ролей ({@link RolePermissions}) на каждый запрос, поэтому настройки разделов
 * менеджера организации (OBSERVER) действуют сразу, без повторного входа.
 */
@Service("access")
@RequiredArgsConstructor
public class AccessService {

	private final AppSettingsService appSettingsService;


	public boolean can(String permission) {
		Permission parsed;
		try {
			parsed = Permission.valueOf(permission);
		} catch (IllegalArgumentException | NullPointerException e) {
			// Опечатка в @PreAuthorize не должна открывать доступ
			return false;
		}
		return can(parsed);
	}


	/** Есть хотя бы одно из прав. */
	public boolean canAny(String... permissions) {
		return Arrays.stream(permissions).anyMatch(this::can);
	}


	public boolean can(Permission permission) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null && authentication.isAuthenticated()
				&& permissions(roles(authentication.getAuthorities())).contains(permission);
	}


	public Set<Permission> permissions(Collection<Role> roles) {
		Set<Permission> result = EnumSet.noneOf(Permission.class);
		for (Role role : roles) {
			Set<Permission> granted = EnumSet.noneOf(Permission.class);
			granted.addAll(RolePermissions.of(role));
			if (role == Role.OBSERVER) {
				Set<ObserverFeature> enabled = appSettingsService.getObserverFeatures();
				for (ObserverFeature feature : ObserverFeature.values()) {
					if (!enabled.contains(feature)) {
						granted.removeAll(feature.getPermissions());
					}
				}
			}
			result.addAll(granted);
		}
		return result;
	}


	public static Set<Role> roles(Collection<? extends GrantedAuthority> authorities) {
		if (authorities == null) {
			return Set.of();
		}
		return authorities.stream()
				.filter(Objects::nonNull)
				.map(GrantedAuthority::getAuthority)
				.filter(Objects::nonNull)
				.map(authority -> authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)
				.map(AccessService::parseRole)
				.filter(Objects::nonNull)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
	}


	private static Role parseRole(String name) {
		try {
			return Role.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

}
