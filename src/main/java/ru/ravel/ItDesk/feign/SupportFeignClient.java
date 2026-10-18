package ru.ravel.ItDesk.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import ru.ravel.ItDesk.dto.PasswordResetConfirm;
import ru.ravel.ItDesk.dto.PasswordResetLinkRequest;
import ru.ravel.ItDesk.model.Message;

import java.util.Map;
import java.util.UUID;


@FeignClient(name = "support", url = "${app.license-server-url}")
public interface SupportFeignClient {

	@PostMapping("/api/v1/support/{license}/new-message")
	void newMessage(@PathVariable UUID license, @RequestBody Message message);

	/** Админ-система генерирует одноразовую ссылку и отправляет её пользователю на email. */
	@PostMapping("/api/v1/support/{license}/password-reset-link")
	void requestPasswordResetLink(@PathVariable UUID license, @RequestBody PasswordResetLinkRequest request);

	/** 200 {expiresAt}, если ссылка действует; 410, если нет. Ссылка не гасится. */
	@PostMapping("/api/v1/support/{license}/password-reset-link/verify")
	Map<String, Object> verifyPasswordResetLink(@PathVariable UUID license, @RequestBody PasswordResetConfirm token);

	/** Гасит ссылку: 200 {email}; 410, если ссылка недействительна или уже использована. */
	@PostMapping("/api/v1/support/{license}/password-reset-link/consume")
	Map<String, Object> consumePasswordResetLink(@PathVariable UUID license, @RequestBody PasswordResetConfirm token);

}
