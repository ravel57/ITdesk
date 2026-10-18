package ru.ravel.ItDesk.model;

/**
 * Как ULDesk получает состояние из Zabbix.
 */
public enum ZabbixIntegrationMode {
	/** ULDesk опрашивает API Zabbix (Zabbix доступен из сети ULDesk) */
	API_POLLING,
	/** Zabbix сам отправляет события в ULDesk веб-хуком (Zabbix в закрытом контуре клиента) */
	WEBHOOK
}
