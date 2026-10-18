package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Токен из ссылки смены пароля; password — новый пароль (только при подтверждении). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetConfirm {
	private String token;
	private String password;
}
