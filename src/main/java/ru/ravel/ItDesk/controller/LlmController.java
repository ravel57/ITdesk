package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ravel.ItDesk.service.LlmService;

@RestController
@RequestMapping("/api/v1/llm")
@CrossOrigin
@RequiredArgsConstructor
@Tag(name = "LLM", description = "Запросы к подключенной языковой модели.")
public class LlmController {

	private final LlmService llmService;

	@Operation(summary = "Выполнить LLM-запрос")
	@GetMapping("/query")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getLlmQuery(@RequestParam String query) {
		return ResponseEntity.ok(llmService.askLlm(query));
	}

	@Operation(summary = "Переобучить LLM на актуальной базе знаний")
	@PostMapping("/retrain")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Object> retrainLlm() {
		return ResponseEntity.ok(llmService.retrainFromKnowledgeBase());
	}

	@Operation(summary = "Получить количество чанков БЗ в LLM-индексе текущего инстанса")
	@GetMapping("/kb-count")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Object> getLlmKbCount() {
		return ResponseEntity.ok(llmService.getKbCount());
	}
}