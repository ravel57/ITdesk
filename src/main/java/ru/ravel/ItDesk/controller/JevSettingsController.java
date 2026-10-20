package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.component.LicenseStarter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import ru.ravel.ItDesk.dto.JevSettingsRequest;
import ru.ravel.ItDesk.model.jev.JevDecision;
import ru.ravel.ItDesk.repository.JevDecisionRepository;
import ru.ravel.ItDesk.service.JevDecisionObjectService;
import ru.ravel.ItDesk.service.JevSettingsService;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/v1/settings/jev")
@CrossOrigin
@RequiredArgsConstructor
@Hidden
@PreAuthorize("@access.can('SETTINGS_MANAGE')")
public class JevSettingsController {

	private final JevSettingsService jevSettingsService;
	private final JevDecisionRepository jevDecisionRepository;
	private final JevDecisionObjectService decisionObjectService;


	@GetMapping
	public ResponseEntity<Object> get() {
		return ResponseEntity.ok(jevSettingsService.get());
	}


	@PatchMapping
	public ResponseEntity<Object> update(@RequestBody JevSettingsRequest request) {
		if (!Boolean.TRUE.equals(LicenseStarter.isLicenseActive)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Лицензия не активна");
		}
		try {
			return ResponseEntity.ok(jevSettingsService.update(request));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}


	/**
	 * Журнал запросов и ответов Jev за последние {@code days} дней.
	 */
	@GetMapping("/decisions")
	public Map<String, Object> decisions(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "50") int size,
			@RequestParam(defaultValue = "7") int days,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) String question,
			@RequestParam(required = false) String outcome,
			@RequestParam(required = false) Long ruleId,
			@RequestParam(required = false) String nodeId
	) {
		Page<JevDecision> result = jevDecisionRepository.search(
				since(days), blankToNull(action), blankToNull(question), blankToNull(outcome), ruleId, blankToNull(nodeId),
				PageRequest.of(Math.max(0, page - 1), Math.min(200, Math.max(1, size))));
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("decisions", result.getContent());
		response.put("totalElements", result.getTotalElements());
		// Подписи объектов из context («client:1» → «Иван Петров»), чтобы журнал показывал не только id
		response.put("objectLabels", decisionObjectService.labels(result.getContent()));
		return response;
	}


	/**
	 * Предпросмотр объекта из контекста решения: клиент, сообщение, заявка, инцидент или сервис.
	 * clientId — клиент из того же контекста, нужен для ссылки на чат сообщения.
	 */
	@GetMapping("/decisions/object")
	public ResponseEntity<Object> decisionObject(
			@RequestParam String type,
			@RequestParam Long id,
			@RequestParam(required = false) Long clientId
	) {
		return decisionObjectService.preview(type, id, clientId)
				.<ResponseEntity<Object>>map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Объект не найден: возможно, он удалён")));
	}


	/**
	 * Сводка по сценарию, шагу и вопросу: ответы, ошибки, пропуски и ожидаемые запросы.
	 */
	@GetMapping("/decisions/stats")
	public List<Map<String, Object>> decisionStats(@RequestParam(defaultValue = "7") int days) {
		return jevDecisionRepository.stats(since(days)).stream().map(row -> {
			Map<String, Object> item = new LinkedHashMap<>();
            String[] keys = {"ruleId", "ruleName", "nodeId", "nodeLabel", "action", "question", "questionTitle", "answerType",
                    "total", "avgConfidence", "answered", "failed", "skipped", "pending"};
            for (int i = 0; i < keys.length; i++) item.put(keys[i], row[i]);
			return item;
		}).toList();
	}


	private ZonedDateTime since(int days) {
		return ZonedDateTime.now().minusDays(Math.min(30, Math.max(1, days)));
	}


	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
