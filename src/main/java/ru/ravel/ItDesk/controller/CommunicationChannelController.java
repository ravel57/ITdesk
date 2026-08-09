package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
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
@Tag(name = "Каналы связи", description = "Настройка Telegram, email и WhatsApp каналов.")
public class CommunicationChannelController {

	private final TelegramService telegramService;
	private final EmailService emailService;
	private final WhatsappService whatsappService;


	@Operation(summary = "Получить Telegram-ботов", description = "Получить Telegram-ботов. Endpoint GET /api/v1/telegram-bots; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/telegram-bots")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getTelegramBots() {
		return ResponseEntity.ok().body(telegramService.getTelegramBots());
	}


	@Operation(summary = "Добавить Telegram-бота", description = "Добавить Telegram-бота. Endpoint POST /api/v1/telegram-bot; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/telegram-bot")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newTelegramBot(@RequestBody TgBot telegramBot) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(telegramService.newTelegramBot(telegramBot));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Обновить Telegram-бота", description = "Обновить Telegram-бота. Endpoint PATCH /api/v1/telegram-bot; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/telegram-bot")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateTelegramBot(@RequestBody TgBot telegramBot) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(telegramService.updateTelegramBot(telegramBot));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Удалить Telegram-бота", description = "Удалить Telegram-бота. Endpoint DELETE /api/v1/telegram-bot/{tgBotId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/telegram-bot/{tgBotId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteTelegramBot(@PathVariable Long tgBotId) {
		if (LicenseStarter.isLicenseActive) {
			telegramService.deleteTelegramBot(tgBotId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Получить email-аккаунты", description = "Получить email-аккаунты. Endpoint GET /api/v1/emails; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/emails")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getEmailAccounts() {
		return ResponseEntity.ok().body(emailService.getEmailsAccounts());
	}


	@Operation(summary = "Добавить email-аккаунт", description = "Добавить email-аккаунт. Endpoint POST /api/v1/email; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/email")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newEmailAccount(@RequestBody EmailAccount emailAccount) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(emailService.newEmailAccount(emailAccount));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Обновить email-аккаунт", description = "Обновить email-аккаунт. Endpoint PATCH /api/v1/email; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/email")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateEmailAccount(@RequestBody EmailAccount emailAccount) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(emailService.updateEmailAccount(emailAccount));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Удалить email-аккаунт", description = "Удалить email-аккаунт. Endpoint DELETE /api/v1/email/{emailAccountId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/email/{emailAccountId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteEmailAccount(@PathVariable Long emailAccountId) {
		if (LicenseStarter.isLicenseActive) {
			emailService.deleteEmailAccount(emailAccountId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Получить WhatsApp-аккаунты", description = "Получить WhatsApp-аккаунты. Endpoint GET /api/v1/whatsapp; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/whatsapp")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getWhatsappAccounts() {
		return ResponseEntity.ok().body(whatsappService.getWhatsappAccounts());
	}


	@Operation(summary = "Добавить WhatsApp-аккаунт", description = "Добавить WhatsApp-аккаунт. Endpoint POST /api/v1/whatsapp; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/whatsapp")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newWhatsappAccount(@RequestBody WhatsappAccount whatsappAccount) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(whatsappService.newWhatsappAccount(whatsappAccount));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Обновить WhatsApp-аккаунт", description = "Обновить WhatsApp-аккаунт. Endpoint PATCH /api/v1/whatsapp; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/whatsapp")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateWhatsappAccount(@RequestBody WhatsappAccount whatsappAccount) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(whatsappService.updateWhatsappAccount(whatsappAccount));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Удалить WhatsApp-аккаунт", description = "Удалить WhatsApp-аккаунт. Endpoint DELETE /api/v1/whatsapp/{accountId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/whatsapp/{accountId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteWhatsappAccount(@PathVariable Long accountId) {
		if (LicenseStarter.isLicenseActive) {
			whatsappService.deleteWhatsappAccount(accountId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}
}
