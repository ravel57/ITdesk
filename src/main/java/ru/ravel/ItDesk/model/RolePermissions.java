package ru.ravel.ItDesk.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static ru.ravel.ItDesk.model.Permission.*;


/**
 * Права ролей и иерархия ролей — единственное место, где роль связывается с возможностями.
 * Иерархия: ADMIN > MANAGER > SENIOR_OPERATOR > OPERATOR; старшая роль получает все права младшей.
 * OBSERVER (менеджер организации) и CLIENT вне иерархии.
 */
public final class RolePermissions {

	/** Для Spring Security {@code RoleHierarchyImpl.fromHierarchy}. */
	public static final String HIERARCHY = """
			ROLE_ADMIN > ROLE_MANAGER
			ROLE_MANAGER > ROLE_SENIOR_OPERATOR
			ROLE_SENIOR_OPERATOR > ROLE_OPERATOR
			""";

	private static final Map<Role, Set<Permission>> PERMISSIONS = new EnumMap<>(Role.class);

	static {
		Set<Permission> observer = EnumSet.of(
				CHAT_VIEW, FILE_VIEW, TASK_VIEW, INCIDENT_VIEW, SERVICE_VIEW, REFERENCE_VIEW,
				SLA_VIEW, ANALYTICS_VIEW, PLUGIN_USE, NOTIFICATIONS
		);
		Set<Permission> operator = EnumSet.copyOf(observer);
		operator.addAll(EnumSet.of(
				CHAT_REPLY, CLIENT_EDIT, FILE_UPLOAD, TASK_EDIT, GLOBAL_SEARCH, INCIDENT_EDIT, KB_VIEW,
				MONITORING_USE, STAFF_DIRECTORY_VIEW, ORGANIZATION_STATS, ORGANIZATION_VISITS, AUTOMATION_APPROVE
		));
		Set<Permission> seniorOperator = EnumSet.copyOf(operator);
		seniorOperator.addAll(EnumSet.of(CHAT_MESSAGE_DELETE, KB_EDIT, TASK_EXPORT));
		Set<Permission> manager = EnumSet.copyOf(seniorOperator);
		manager.addAll(EnumSet.of(
				INCIDENT_TEMPLATE_MANAGE, SERVICE_MANAGE, MONITORING_MANAGE, REFERENCE_MANAGE, ORGANIZATION_MANAGE,
				SLA_MANAGE, AUTOMATION_MANAGE, SETTINGS_VIEW
		));
		Set<Permission> admin = EnumSet.allOf(Permission.class);
		admin.remove(PORTAL);

		PERMISSIONS.put(Role.OBSERVER, Collections.unmodifiableSet(observer));
		PERMISSIONS.put(Role.OPERATOR, Collections.unmodifiableSet(operator));
		PERMISSIONS.put(Role.SENIOR_OPERATOR, Collections.unmodifiableSet(seniorOperator));
		PERMISSIONS.put(Role.MANAGER, Collections.unmodifiableSet(manager));
		PERMISSIONS.put(Role.ADMIN, Collections.unmodifiableSet(admin));
		PERMISSIONS.put(Role.CLIENT, Collections.unmodifiableSet(EnumSet.of(PORTAL, FILE_VIEW, FILE_UPLOAD)));
	}

	private RolePermissions() {
	}


	public static Set<Permission> of(Role role) {
		return role == null ? Set.of() : PERMISSIONS.getOrDefault(role, Set.of());
	}


	/** Роль та же или старше по иерархии (ADMIN включает MANAGER, MANAGER — SENIOR_OPERATOR и т. д.). */
	public static boolean includes(Role role, Role required) {
		if (role == null || required == null) {
			return false;
		}
		if (role == required) {
			return true;
		}
		return rank(role) > 0 && rank(required) > 0 && rank(role) > rank(required);
	}


	private static int rank(Role role) {
		return switch (role) {
			case ADMIN -> 4;
			case MANAGER -> 3;
			case SENIOR_OPERATOR -> 2;
			case OPERATOR -> 1;
			default -> 0;
		};
	}

}
