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
@PreAuthorize("hasRole('ADMIN')")
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
	 * Журнал решений Jev за последние {@code days} дней (для настройки порогов уверенности).
	 */
	@GetMapping("/decisions")
	public Map<String, Object> decisions(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "50") int size,
			@RequestParam(defaultValue = "7") int days,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) String question,
			@RequestParam(required = false) String outcome
	) {
		Page<JevDecision> result = jevDecisionRepository.search(
				since(days), blankToNull(action), blankToNull(question), blankToNull(outcome),
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
	 * Сводка по каждому действию автоматизации и вопросу: число решений, средняя уверенность, доля принятых,
	 * число решений рядом с порогом (±0.1) — главный ориентир для калибровки.
	 */
	@GetMapping("/decisions/stats")
	public List<Map<String, Object>> decisionStats(@RequestParam(defaultValue = "7") int days) {
		return jevDecisionRepository.stats(since(days)).stream().map(row -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("action", row[0]);
			item.put("question", row[1]);
			item.put("total", row[2]);
			item.put("avgConfidence", row[3]);
			item.put("accepted", row[4]);
			item.put("rejected", row[5]);
			item.put("none", row[6]);
			item.put("nearThreshold", row[7]);
			item.put("threshold", row[8]);
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
