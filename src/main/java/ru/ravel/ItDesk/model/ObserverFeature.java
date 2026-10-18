package ru.ravel.ItDesk.model;

import lombok.Getter;

import java.util.Set;


/**
 * Разделы, которые администратор может открыть или закрыть менеджеру организации (OBSERVER).
 * Чаты своих организаций наблюдатель видит всегда.
 */
@Getter
public enum ObserverFeature {

	TASKS("Заявки", Set.of(Permission.TASK_VIEW)),
	INCIDENTS("Инциденты", Set.of(Permission.INCIDENT_VIEW)),
	ANALYTICS("Аналитика", Set.of(Permission.ANALYTICS_VIEW)),
	SLA("Матрица SLA", Set.of(Permission.SLA_VIEW)),
	SERVICES("Услуги и мониторинг", Set.of(Permission.SERVICE_VIEW)),
	;

	private final String label;
	private final Set<Permission> permissions;

	ObserverFeature(String label, Set<Permission> permissions) {
		this.label = label;
		this.permissions = permissions;
	}
}
