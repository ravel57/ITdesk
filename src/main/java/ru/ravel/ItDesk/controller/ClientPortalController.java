package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.dto.ClientPortalMessageRequest;
import ru.ravel.ItDesk.service.ClientPortalService;


@RestController
@RequestMapping("/api/v1/portal")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CLIENT')")
@Hidden
@Tag(name = "Клиентский портал", description = "Профиль клиента и обмен сообщениями через клиентский портал.")
public class ClientPortalController {

	private final ClientPortalService clientPortalService;

	@Operation(summary = "Получить профиль клиента", description = "Возвращает профиль текущего пользователя клиентского портала.")
	@GetMapping("/profile")
	public ResponseEntity<Object> profile() {
		return ResponseEntity.ok(clientPortalService.getProfile());
	}

	@Operation(summary = "Получить сообщения клиентского портала", description = "Возвращает страницу сообщений текущего клиента.")
	@GetMapping("/messages")
	public ResponseEntity<Object> messages(@RequestParam(defaultValue = "1") Integer page) {
		return ResponseEntity.ok(clientPortalService.getMessages(page));
	}

	@Operation(summary = "Отправить сообщение из клиентского портала", description = "Создаёт сообщение от текущего клиента с сохранением действующих лицензионных и бизнес-проверок.")
	@PostMapping("/messages")
	public ResponseEntity<Object> sendMessage(@RequestBody ClientPortalMessageRequest request) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			return ResponseEntity.ok(clientPortalService.sendMessage(request));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
		}
	}
}