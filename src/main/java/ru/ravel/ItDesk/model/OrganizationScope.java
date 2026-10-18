package ru.ravel.ItDesk.model;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * Какие организации видит пользователь. Единственный источник правды для фильтрации по организациям:
 * {@code UserService.organizationScope(user)}. По умолчанию закрыт: неизвестная роль не видит ничего.
 *
 * @param all             все организации (ADMIN, MANAGER, оператор без ограничения)
 * @param organizationIds разрешённые организации, если не all
 */
public record OrganizationScope(boolean all, Set<Long> organizationIds) {

	public static final OrganizationScope ALL = new OrganizationScope(true, Set.of());
	public static final OrganizationScope NONE = new OrganizationScope(false, Set.of());


	public static OrganizationScope of(Collection<Long> organizationIds) {
		Set<Long> ids = organizationIds == null ? Set.of() : organizationIds.stream()
				.filter(Objects::nonNull)
				.collect(Collectors.toUnmodifiableSet());
		return ids.isEmpty() ? NONE : new OrganizationScope(false, ids);
	}


	public boolean restricted() {
		return !all;
	}


	/** Объект без организации доступен только при доступе ко всем организациям. */
	public boolean allows(Long organizationId) {
		return all || organizationId != null && organizationIds.contains(organizationId);
	}


	public <T> List<T> filter(Collection<T> items, Function<T, Long> organizationId) {
		if (items == null) {
			return List.of();
		}
		return items.stream()
				.filter(Objects::nonNull)
				.filter(item -> allows(organizationId.apply(item)))
				.toList();
	}

}
