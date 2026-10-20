package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Запрос в админ-систему: отправить пользователю ссылку на страницу смены пароля этой инсталляции. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetLinkRequest {
	private String email;
	private String baseUrl;
	/** "INVITE" — приглашение новому пользователю; иначе (в т.ч. не указано) — обычный сброс пароля. */
	private String purpose;
}
