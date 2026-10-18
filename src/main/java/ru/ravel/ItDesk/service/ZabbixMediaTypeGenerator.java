package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.model.ZabbixIntegration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Тип оповещений «ULDesk» для импорта в Zabbix 6.0+ (Оповещения → Способы оповещений → Импорт).
 * Скрипт веб-хука — resources/zabbix/uldesk-webhook.js, контракт — uldesk.zabbix.v1.
 */
@Component
@RequiredArgsConstructor
public class ZabbixMediaTypeGenerator {

	public static final String TOKEN_PLACEHOLDER = "ВСТАВЬТЕ_ТОКЕН";

	private final ZabbixWebhookService webhookService;


	public String fileName(ZabbixIntegration integration) {
		return "zbx_mediatype_uldesk_" + integration.getId() + ".yaml";
	}


	/**
	 * @param token токен интеграции, если он только что выпущен; иначе в файле будет заглушка
	 */
	public String yaml(ZabbixIntegration integration, String token) {
		Map<String, String> parameters = new LinkedHashMap<>();
		parameters.put("uldesk_url", webhookService.eventsUrl(integration));
		parameters.put("uldesk_token", token == null || token.isBlank() ? TOKEN_PLACEHOLDER : token.trim());
		parameters.put("http_proxy", "");
		parameters.put("zabbix_url", zabbixUrl(integration));
		parameters.put("event_id", "{EVENT.ID}");
		parameters.put("event_value", "{EVENT.VALUE}");
		parameters.put("event_update_status", "{EVENT.UPDATE.STATUS}");
		parameters.put("event_name", "{EVENT.NAME}");
		parameters.put("event_nseverity", "{EVENT.NSEVERITY}");
		parameters.put("event_severity", "{EVENT.SEVERITY}");
		parameters.put("event_date", "{EVENT.DATE}");
		parameters.put("event_time", "{EVENT.TIME}");
		parameters.put("event_recovery_date", "{EVENT.RECOVERY.DATE}");
		parameters.put("event_recovery_time", "{EVENT.RECOVERY.TIME}");
		parameters.put("event_ack", "{EVENT.ACK.STATUS}");
		parameters.put("event_opdata", "{EVENT.OPDATA}");
		parameters.put("event_tags", "{EVENT.TAGSJSON}");
		parameters.put("event_update_action", "{EVENT.UPDATE.ACTION}");
		parameters.put("event_update_message", "{EVENT.UPDATE.MESSAGE}");
		parameters.put("event_update_user", "{USER.FULLNAME}");
		parameters.put("trigger_id", "{TRIGGER.ID}");
		parameters.put("trigger_description", "{TRIGGER.DESCRIPTION}");
		parameters.put("host_id", "{HOST.ID}");
		parameters.put("host_host", "{HOST.HOST}");
		parameters.put("host_name", "{HOST.NAME}");
		parameters.put("host_ip", "{HOST.IP}");
		parameters.put("host_groups", "{TRIGGER.HOSTGROUP.NAME}");

		StringBuilder yaml = new StringBuilder();
		yaml.append("zabbix_export:\n");
		yaml.append("  version: '6.0'\n");
		yaml.append("  media_types:\n");
		yaml.append("    - name: ").append(quote("ULDesk: " + integration.getName())).append('\n');
		yaml.append("      type: WEBHOOK\n");
		yaml.append("      parameters:\n");
		parameters.forEach((name, value) -> yaml
				.append("        - name: ").append(name).append('\n')
				.append("          value: ").append(quote(value)).append('\n'));
		// Имена тегов — по схеме импорта Zabbix 6.0 (C60XmlValidator): attempts, а не max_attempts как в API
		yaml.append("      attempts: '5'\n");
		yaml.append("      attempt_interval: 30s\n");
		yaml.append("      script: |\n");
		for (String line : script().split("\n", -1)) {
			yaml.append(line.isEmpty() ? "" : "        " + line).append('\n');
		}
		yaml.append("      timeout: 10s\n");
		yaml.append("      process_tags: 'YES'\n");
		yaml.append("      show_event_menu: 'YES'\n");
		yaml.append("      event_menu_url: '{EVENT.TAGS.__uldesk_url}'\n");
		yaml.append("      event_menu_name: 'ULDesk: {EVENT.TAGS.__uldesk_ref}'\n");
		yaml.append("      description: ").append(quote(
				"Отправляет события Zabbix в ULDesk (контракт uldesk.zabbix.v1). "
						+ "Привязка к сервису: тег uldesk_service=<код сервиса> на триггере или узле.")).append('\n');
		yaml.append("      message_templates:\n");
		for (String mode : new String[]{"PROBLEM", "RECOVERY", "UPDATE"}) {
			yaml.append("        - event_source: TRIGGERS\n");
			yaml.append("          operation_mode: ").append(mode).append('\n');
			yaml.append("          subject: '{EVENT.NAME}'\n");
			yaml.append("          message: '{EVENT.NAME} ({EVENT.SEVERITY}) на {HOST.NAME}'\n");
		}
		return yaml.toString();
	}


	private String zabbixUrl(ZabbixIntegration integration) {
		String base = integration.getBaseUrl();
		if (base == null || base.isBlank()) {
			return "{$ZABBIX.URL}";
		}
		return base.trim().replaceAll("/?api_jsonrpc\\.php$", "").replaceAll("/+$", "");
	}


	private String script() {
		try (InputStream input = new ClassPathResource("zabbix/uldesk-webhook.js").getInputStream()) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").stripTrailing();
		} catch (IOException e) {
			throw new UncheckedIOException("Не найден скрипт веб-хука Zabbix", e);
		}
	}


	private static String quote(String value) {
		return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
	}

}
