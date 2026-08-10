package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.service.GlobalSearchAccessService;
import ru.ravel.ItDesk.service.GlobalSearchService;

import java.util.HashSet;
import java.util.Set;


@RestController
@RequestMapping("/api/v1")
@CrossOrigin
@RequiredArgsConstructor
@Hidden
@Tag(name = "Глобальный поиск", description = "Поиск по индексируемым сущностям и обслуживание поискового индекса.")
public class GlobalSearchController {

	private final GlobalSearchService globalSearchService;
	private final GlobalSearchAccessService globalSearchAccessService;


	@Operation(summary = "Выполнить глобальный поиск", description = "Выполнить глобальный поиск. Endpoint GET /api/v1/global-search; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/global-search")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> globalSearch(
			@RequestParam String query,
			@RequestParam(required = false) String types
	) {
		return ResponseEntity.ok().body(
				globalSearchAccessService.filterForCurrentUser(
						globalSearchService.search(query, parseGlobalSearchTypes(types))
				)
		);
	}


	private Set<String> parseGlobalSearchTypes(String types) {
		if (types == null || types.isBlank()) {
			return Set.of();
		}
		Set<String> result = new HashSet<>();
		for (String type : types.split(",")) {
			if (type.isBlank()) {
				continue;
			}
			result.add(type.trim());
		}
		return result;
	}


	@Operation(summary = "Переиндексировать глобальный поиск", description = "Переиндексировать глобальный поиск. Endpoint POST /api/v1/global-search/reindex; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/global-search/reindex")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> reindexGlobalSearch() {
		return ResponseEntity.ok().body(globalSearchService.reindexAll());
	}


	@Operation(summary = "Получить число документов в индексе", description = "Получить число документов в индексе. Endpoint GET /api/v1/global-search/count; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/global-search/count")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Object> countGlobalSearchDocuments() {
		return ResponseEntity.ok().body(globalSearchService.countDocuments());
	}
}