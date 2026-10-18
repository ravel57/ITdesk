package ru.ravel.ItDesk.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import ru.ravel.ItDesk.model.Permission;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.RolePermissions;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Таблица прав, собранная из самих контроллеров: каждый HTTP-метод защищён правом из {@link Permission}
 * (или явно публичен), а документ docs/access-matrix.md совпадает с кодом.
 * Изменили права — перегенерируйте документ: {@code ACCESS_MATRIX_UPDATE=true sh ./gradlew test --tests '*AccessMatrixTest'}.
 */
class AccessMatrixTest {

	private static final Path DOCUMENT = Path.of("src/main/resources/docs/access-matrix.md");
	private static final Pattern PERMISSION = Pattern.compile("'(\\w+)'");
	private static final Pattern EXPRESSION = Pattern.compile("@access\\.(can|canAny)\\(('\\w+'(,\\s*'\\w+')*)\\)|isAuthenticated\\(\\)");

	/** Открытые адреса и адреса, где достаточно входа (проверка по URL-правилам WebSecurityConfig). */
	private static final Map<String, String> PUBLIC = Map.of(
			"CsrfController.csrf", "любой, CSRF-токен",
			"SupportController.resetPassword", "без входа: запрос ссылки смены пароля",
			"SupportController.verifyResetPasswordLink", "без входа: проверка ссылки",
			"SupportController.confirmResetPassword", "без входа: новый пароль по ссылке",
			"UserController.changePassword", "любой вошедший: свой пароль",
			"UserController.userOnline", "любой вошедший",
			"UserController.userOffline", "любой вошедший",
			"WebController.spaMapping", "страницы SPA",
			"ZabbixWebhookController.receiveEvent", "Zabbix: токен интеграции",
			"ZabbixWebhookController.heartbeat", "Zabbix: токен интеграции"
	);

	private static final List<Role> ROLES = List.of(Role.ADMIN, Role.MANAGER, Role.SENIOR_OPERATOR, Role.OPERATOR, Role.OBSERVER, Role.CLIENT);


	record Endpoint(String key, String httpMethod, String path, String rule, Set<Role> roles) {
	}


	@Test
	void everyEndpointIsProtectedByKnownPermissionOrExplicitlyPublic() {
		List<String> problems = new ArrayList<>();
		Set<String> seenPublic = new HashSet<>();
		for (Endpoint endpoint : endpoints()) {
			if (endpoint.rule() == null) {
				if (PUBLIC.containsKey(endpoint.key())) {
					seenPublic.add(endpoint.key());
				} else {
					problems.add(endpoint.key() + ": нет @PreAuthorize и не в списке публичных");
				}
			} else if (!EXPRESSION.matcher(endpoint.rule()).matches()) {
				problems.add(endpoint.key() + ": проверка не через права: " + endpoint.rule());
			}
		}
		assertTrue(problems.isEmpty(), String.join("\n", problems));
		assertEquals(PUBLIC.keySet(), seenPublic, "список публичных адресов устарел");
	}


	@Test
	void permissionsUsedInControllersExist() {
		for (Endpoint endpoint : endpoints()) {
			if (endpoint.rule() == null) {
				continue;
			}
			Matcher matcher = PERMISSION.matcher(endpoint.rule());
			while (matcher.find()) {
				String name = matcher.group(1);
				assertDoesNotThrow(() -> Permission.valueOf(name), endpoint.key() + ": нет права " + name);
			}
		}
	}


	@Test
	void hierarchyGivesSeniorRolesEverythingOfJuniorOnes() {
		assertTrue(RolePermissions.of(Role.SENIOR_OPERATOR).containsAll(RolePermissions.of(Role.OPERATOR)));
		assertTrue(RolePermissions.of(Role.MANAGER).containsAll(RolePermissions.of(Role.SENIOR_OPERATOR)));
		assertTrue(RolePermissions.of(Role.ADMIN).containsAll(RolePermissions.of(Role.MANAGER)));
		assertTrue(RolePermissions.of(Role.OPERATOR).containsAll(RolePermissions.of(Role.OBSERVER)));
		assertTrue(RolePermissions.includes(Role.ADMIN, Role.OPERATOR));
		assertFalse(RolePermissions.includes(Role.OPERATOR, Role.SENIOR_OPERATOR));
		assertFalse(RolePermissions.includes(Role.ADMIN, Role.OBSERVER));
	}


	@Test
	void documentMatchesCode() throws IOException {
		String generated = render(endpoints());
		if ("true".equalsIgnoreCase(System.getenv("ACCESS_MATRIX_UPDATE")) || !Files.exists(DOCUMENT)) {
			Files.writeString(DOCUMENT, generated, StandardCharsets.UTF_8);
		}
		assertEquals(Files.readString(DOCUMENT, StandardCharsets.UTF_8), generated,
				"docs/access-matrix.md устарел: перегенерируйте — ACCESS_MATRIX_UPDATE=true sh ./gradlew test --tests '*AccessMatrixTest'");
	}


	private static List<Endpoint> endpoints() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
		List<Endpoint> result = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents("ru.ravel.ItDesk")) {
			Class<?> type;
			try {
				type = Class.forName(definition.getBeanClassName());
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}
			RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
			String base = classMapping == null || classMapping.path().length == 0 ? "" : classMapping.path()[0];
			PreAuthorize classRule = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
			for (Method method : type.getDeclaredMethods()) {
				RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
				if (mapping == null) {
					continue;
				}
				PreAuthorize rule = Optional.ofNullable(AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class)).orElse(classRule);
				String path = base + (mapping.path().length == 0 ? "" : mapping.path()[0]);
				String httpMethod = mapping.method().length == 0 ? "ANY"
						: Arrays.stream(mapping.method()).map(RequestMethod::name).collect(Collectors.joining(","));
				String key = type.getSimpleName() + "." + method.getName();
				result.add(new Endpoint(key, httpMethod, path.isEmpty() ? "/" : path, rule == null ? null : rule.value(),
						rule == null ? Set.of() : roles(rule.value())));
			}
		}
		result.sort(Comparator.comparing(Endpoint::path).thenComparing(Endpoint::httpMethod).thenComparing(Endpoint::key));
		return result;
	}


	private static Set<Role> roles(String rule) {
		if (rule.equals("isAuthenticated()")) {
			return EnumSet.copyOf(ROLES);
		}
		Set<Permission> permissions = EnumSet.noneOf(Permission.class);
		Matcher matcher = PERMISSION.matcher(rule);
		while (matcher.find()) {
			permissions.add(Permission.valueOf(matcher.group(1)));
		}
		Set<Role> roles = EnumSet.noneOf(Role.class);
		for (Role role : ROLES) {
			if (RolePermissions.of(role).stream().anyMatch(permissions::contains)) {
				roles.add(role);
			}
		}
		return roles;
	}


	private static String render(List<Endpoint> endpoints) {
		StringBuilder out = new StringBuilder();
		out.append("# Таблица прав ULDesk\n\n");
		out.append("Документ генерирует `AccessMatrixTest` из `RolePermissions` и аннотаций контроллеров — руками не править.\n");
		out.append("Иерархия: ADMIN > MANAGER > SENIOR_OPERATOR > OPERATOR. Разделы OBSERVER (менеджера организации) ")
				.append("можно закрыть в настройках: здесь показаны права со всеми открытыми разделами. ")
				.append("Ограничение по организациям — `UserService.organizationScope`.\n\n");

		out.append("## Права ролей\n\n| Право | Описание |");
		ROLES.forEach(role -> out.append(' ').append(role.name()).append(" |"));
		out.append("\n|---|---|");
		ROLES.forEach(role -> out.append("---|"));
		out.append('\n');
		for (Permission permission : Permission.values()) {
			out.append("| `").append(permission.name()).append("` | ").append(permission.getGroup()).append(": ").append(permission.getLabel()).append(" |");
			ROLES.forEach(role -> out.append(RolePermissions.of(role).contains(permission) ? " ✅ |" : " — |"));
			out.append('\n');
		}

		out.append("\n## Адреса API\n\n| Метод | Адрес | Проверка |");
		ROLES.forEach(role -> out.append(' ').append(role.name()).append(" |"));
		out.append("\n|---|---|---|");
		ROLES.forEach(role -> out.append("---|"));
		out.append('\n');
		for (Endpoint endpoint : endpoints) {
			String rule = endpoint.rule() == null ? PUBLIC.getOrDefault(endpoint.key(), "—") : endpoint.rule().replace("@access.", "");
			out.append("| ").append(endpoint.httpMethod()).append(" | `").append(endpoint.path()).append("` | ").append(rule).append(" |");
			ROLES.forEach(role -> out.append(endpoint.rule() == null ? " · |" : endpoint.roles().contains(role) ? " ✅ |" : " — |"));
			out.append('\n');
		}
		return out.toString();
	}

}
