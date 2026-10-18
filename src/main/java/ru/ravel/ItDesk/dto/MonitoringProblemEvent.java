package ru.ravel.ItDesk.dto;

import java.util.List;
import java.util.Map;


/**
 * Данные событий автоматизаций MONITORING_PROBLEM_*: в условиях и действиях доступны как service.*, problem.*, integration.*.
 */
public record MonitoringProblemEvent(ServiceRef service, Problem problem, IntegrationRef integration) {

	/** Сервис каталога, к которому привязана проблема (null — не сопоставлено) */
	public record ServiceRef(Long id, String code, String name) {
	}

	public record IntegrationRef(Long id, String name) {
	}

	/**
	 * Проблема Zabbix. status — OPEN / RESOLVED, eventStatus — что пришло: PROBLEM / RESOLVED / UPDATE.
	 * severity: 0 Not classified, 1 Information, 2 Warning, 3 Average, 4 High, 5 Disaster.
	 */
	public record Problem(
			String eventId,
			String status,
			String eventStatus,
			String name,
			Integer severity,
			String severityName,
			String host,
			String hostName,
			String ip,
			Boolean acknowledged,
			String opdata,
			String url,
			String startedAt,
			String resolvedAt,
			List<Map<String, String>> tags,
			String updateAction,
			String updateMessage,
			String updateUser
	) {
	}
}
