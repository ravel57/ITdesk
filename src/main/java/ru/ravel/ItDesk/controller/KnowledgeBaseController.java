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
@Tag(name = "База знаний", description = "Управление статьями базы знаний.")
public class KnowledgeBaseController {

	private final KnowledgeService knowledgeService;
	private final GlobalSearchService globalSearchService;


	@Operation(summary = "Получить базу знаний", description = "Получить базу знаний. Endpoint GET /api/v1/knowledge-base; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("knowledge-base")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getKnowledgeBase() {
		return ResponseEntity.ok().body(knowledgeService.getKnowledgeBase());
	}


	@Operation(summary = "Создать статью базы знаний", description = "Создать статью базы знаний. Endpoint POST /api/v1/knowledge-base; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("knowledge-base")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> newKnowledge(@RequestBody Knowledge knowledge) {
		if (LicenseStarter.isLicenseActive) {
			Knowledge savedKnowledge = knowledgeService.newKnowledge(knowledge);
			globalSearchService.indexKnowledge(savedKnowledge);
			return ResponseEntity.ok().body(savedKnowledge);
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Обновить статью базы знаний", description = "Обновить статью базы знаний. Endpoint PATCH /api/v1/knowledge-base; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("knowledge-base")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> updateKnowledge(@RequestBody Knowledge knowledge) {
		if (LicenseStarter.isLicenseActive) {
			Knowledge savedKnowledge = knowledgeService.updateKnowledge(knowledge);
			globalSearchService.indexKnowledge(savedKnowledge);
			return ResponseEntity.ok().body(savedKnowledge);
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Удалить статью базы знаний", description = "Удалить статью базы знаний. Endpoint DELETE /api/v1/knowledge-base/{knowledgeId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("knowledge-base/{knowledgeId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> deleteKnowledge(@PathVariable Long knowledgeId) {
		if (LicenseStarter.isLicenseActive) {
			knowledgeService.deleteKnowledge(knowledgeId);
			globalSearchService.deleteKnowledge(knowledgeId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Изменить порядок базы знаний", description = "Изменить порядок базы знаний. Endpoint POST /api/v1/knowledge-base/resort; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("knowledge-base/resort")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> resortKnowledgeBase(@RequestBody List<Knowledge> knowledge) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(knowledgeService.resortKnowledge(knowledge));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}
}
