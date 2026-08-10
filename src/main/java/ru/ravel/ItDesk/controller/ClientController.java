package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.dto.*;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AppSettingsRepository;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.service.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;


@RestController
@RequestMapping("/api/v1")
@CrossOrigin
@RequiredArgsConstructor
@Tag(name = "Клиенты", description = "Карточки клиентов и связанные клиентские данные.")
public class ClientController {

	private final ClientService clientService;
	private final ClientCsvImportService clientCsvImportService;
	private final ClientUserCsvImportService clientUserCsvImportService;


	@Operation(summary = "Получить список клиентов", description = "Получить список клиентов. Endpoint GET /api/v1/clients; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/clients")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getClients() {
		return ResponseEntity.ok().body(clientService.getClients());
	}


	@Operation(summary = "Импортировать клиентов из CSV", description = "Загрузить CSV с клиентами. Поддерживаются стандартные колонки firstname, lastname, email, phoneNumber, organization, moreInfo и распространённые имена полей Active Directory. Существующие клиенты определяются по email.")
	@PostMapping(value = "/clients/import-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Object> importClientsCsv(
			@RequestParam("file") MultipartFile file,
			@RequestParam(value = "updateExisting", defaultValue = "false") boolean updateExisting
	) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			return ResponseEntity.ok(clientCsvImportService.importCsv(file, updateExisting));
		} catch (IllegalArgumentException exception) {
			return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
		} catch (Exception exception) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", "Не удалось прочитать CSV-файл"));
		}
	}


	@Operation(summary = "Скачать шаблон CSV для импорта клиентов")
	@GetMapping(value = "/clients/import-csv/template", produces = "text/csv")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<byte[]> getClientsCsvTemplate() {
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=clients-import-template.csv")
				.contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
				.body(clientCsvImportService.getTemplate());
	}


	@Operation(summary = "Скачать шаблон CSV для импорта клиентских учётных записей")
	@GetMapping(value = "/client-users/import-csv/template", produces = "text/csv")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<byte[]> getClientUsersCsvTemplate() {
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=client-users-import-template.csv")
				.contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
				.body(clientUserCsvImportService.getTemplate());
	}


	@Operation(summary = "Предпросмотр импорта клиентских учётных записей из CSV", description = "Разбирает CSV, нормализует поля AD и возвращает редактируемые строки без создания пользователей.")
	@PostMapping(value = "/client-users/import-csv/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Object> previewClientUsersCsv(@RequestParam("file") MultipartFile file) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			return ResponseEntity.ok(clientUserCsvImportService.preview(file));
		} catch (IllegalArgumentException exception) {
			return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
		} catch (Exception exception) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("error", "Не удалось прочитать CSV-файл"));
		}
	}


	@Operation(summary = "Импортировать клиентские учётные записи после предпросмотра", description = "Создаёт выбранные клиентские учётные записи, генерирует пароли и возвращает пароли только в ответе этой операции.")
	@PostMapping(value = "/client-users/import-csv")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Object> importClientUsersCsv(@RequestBody ClientUserImportRequest request) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			return ResponseEntity.ok(clientUserCsvImportService.importRows(request));
		} catch (IllegalArgumentException exception) {
			return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
		}
	}


	@Operation(summary = "Получить файлы клиента", description = "Получить файлы клиента. Endpoint GET /api/v1/client-files/{clientId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/client-files/{clientId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getClientFiles(@PathVariable Long clientId) {
		return ResponseEntity.ok().body(clientService.getClientFiles(clientId));
	}


	@Operation(summary = "Обновить клиента", description = "Обновить клиента. Endpoint PATCH /api/v1/client/{clientId}/client; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/client/{clientId}/client")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> updateClient(@PathVariable Long clientId, @RequestBody Map<String, Object> client) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(clientService.updateClient(clientId, client));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Удалить клиента", description = "Удалить клиента. Endpoint DELETE /api/v1/client/{clientId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/client/{clientId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteClient(@PathVariable Long clientId) {
		if (LicenseStarter.isLicenseActive) {
			boolean isDeleted = clientService.deleteClient(clientId);
			if (isDeleted) {
				return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
			} else {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
			}
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}
}