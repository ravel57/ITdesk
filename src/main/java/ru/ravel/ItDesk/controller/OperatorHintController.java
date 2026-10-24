package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.service.OperatorHintService;

import java.util.Map;
import java.util.NoSuchElementException;


@RestController
@RequestMapping("/api/v1/operator-hints")
@RequiredArgsConstructor
@Tag(name = "Подсказки оператору", description = "Подсказки Jev в чатах и их оценка операторами.")
public class OperatorHintController {

	private final OperatorHintService hintService;


	@Operation(summary = "Подсказки в чате клиента", description = "Подсказки Jev, опубликованные в чате клиента, с источником и оценкой.")
	@GetMapping
	@PreAuthorize("@access.can('CHAT_VIEW')")
	public ResponseEntity<Object> hints(@RequestParam Long clientId) {
		try {
			return ResponseEntity.ok(hintService.hintsOfClient(clientId));
		} catch (NoSuchElementException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}
	}


	@Operation(summary = "Оценить подсказку", description = "helpful: true — помогло, false — не помогло, null — снять оценку.")
	@PostMapping("/{hintId}/feedback")
	@PreAuthorize("@access.can('CHAT_REPLY')")
	public ResponseEntity<Object> rate(@PathVariable Long hintId, @RequestBody Map<String, Boolean> body) {
		try {
			return ResponseEntity.ok(hintService.rate(hintId, body.get("helpful")));
		} catch (NoSuchElementException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}
	}


	@Operation(summary = "Статистика подсказок", description = "По правилам и источникам за последние days дней: сколько опубликовано, помогло, не помогло, средняя уверенность Jev; и правила, которые публикуют подсказки. ruleId — только одно правило.")
	@GetMapping("/stats")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> stats(@RequestParam(defaultValue = "30") Integer days, @RequestParam(required = false) Long ruleId) {
		return ResponseEntity.ok(hintService.stats(days, ruleId));
	}

}
