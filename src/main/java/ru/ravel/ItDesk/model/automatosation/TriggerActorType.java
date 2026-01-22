package ru.ravel.ItDesk.model.automatosation;

public enum TriggerActorType {
	SYSTEM,          // автоматика
	AGENT,           // сотрудник поддержки
	CLIENT,          // клиент (внешний пользователь)
	BOT,             // telegram bot / whatsapp bot
	INTEGRATION      // внешняя система
}