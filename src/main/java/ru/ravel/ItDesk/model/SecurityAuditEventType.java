package ru.ravel.ItDesk.model;

import lombok.Getter;


@Getter
public enum SecurityAuditEventType {

	LOGIN_SUCCEEDED("Вход", false),
	LOGIN_FAILED("Неудачный вход", true),
	MFA_SUCCEEDED("Подтверждение кодом 2FA", false),
	MFA_FAILED("Неверный код 2FA", true),
	MFA_ENABLED("Включена двухфакторная аутентификация", false),
	MFA_DISABLED("Отключена двухфакторная аутентификация", true),
	MFA_RESET_BY_ADMIN("Администратор сбросил 2FA пользователя", true),
	PASSWORD_CHANGED("Смена своего пароля", false),
	PASSWORD_CHANGED_BY_ADMIN("Администратор сменил пароль", true),
	PASSWORD_RESET_LINK_REQUESTED("Запрошена ссылка смены пароля", false),
	PASSWORD_CHANGED_BY_RESET_LINK("Пароль изменён по ссылке", false),
	USER_CREATED("Создан пользователь", false),
	USER_UPDATED("Изменён пользователь", false),
	USER_ROLE_CHANGED("Изменена роль пользователя", true),
	USER_DELETED("Отключён пользователь", true),
	CLIENT_DELETED("Удалён клиент", true),
	MESSAGE_DELETED("Удалено сообщение", false),
	TASKS_EXPORTED("Выгрузка заявок", false),
	SETTINGS_CHANGED("Изменены общие настройки", false),
	OBSERVER_ACCESS_CHANGED("Изменены разделы менеджера организации", true),
	PLUGIN_CHANGED("Изменение плагина", true),
	JWT_ISSUED("Выпущен ключ API", true),
	JWT_REVOKED("Отозван ключ API", false),
	JWT_REVOKED_ALL("Отозваны все ключи API", true),
	JWT_REJECTED("Отклонён ключ API", true),
	JWT_SIGNING_KEY_ROTATED("Сменён ключ подписи API", true),
	;

	private final String label;
	/** Событие стоит проверить: показывается выделенным. */
	private final boolean attention;

	SecurityAuditEventType(String label, boolean attention) {
		this.label = label;
		this.attention = attention;
	}
}
