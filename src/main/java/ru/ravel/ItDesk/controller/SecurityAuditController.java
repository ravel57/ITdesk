package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.model.SecurityAuditEvent;
import ru.ravel.ItDesk.model.SecurityAuditEventType;
import ru.ravel.ItDesk.repository.SecurityAuditEventRepository;

import java.time.ZonedDateTime;
import java.util.*;


@RestController
@RequestMapping("/api/v1/security-audit")
@RequiredArgsConstructor
@Tag(name = "Журнал безопасности", description = "Входы, пароли, роли, удаления, выгрузки, ключи API, двухфакторный вход.")
public class SecurityAuditController {

	private static final int MAX_PAGE_SIZE = 200;

	private final SecurityAuditEventRepository repository;


	@Operation(summary = "События журнала безопасности", description = "Фильтры: types (коды через запятую), search (логин, объект, IP, подробности), from, to (ISO-8601). Сначала новые.")
	@GetMapping
	@PreAuthorize("@access.can('SECURITY_AUDIT_VIEW')")
	public ResponseEntity<Object> events(
			@RequestParam(required = false) List<SecurityAuditEventType> types,
			@RequestParam(required = false) String search,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime to,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size
	) {
		boolean allTypes = types == null || types.isEmpty();
		String normalizedSearch = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
		Page<SecurityAuditEvent> result = repository.search(
				allTypes,
				allTypes ? List.of("-") : types.stream().map(Enum::name).toList(),
				normalizedSearch,
				from == null ? ZonedDateTime.now().minusYears(10) : from,
				to == null ? ZonedDateTime.now().plusMinutes(1) : to,
				PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE)))
		);
		List<Map<String, Object>> rows = result.getContent().stream().map(event -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", event.getId());
			row.put("createdAt", event.getCreatedAt());
			row.put("type", event.getType().name());
			row.put("typeLabel", event.getType().getLabel());
			row.put("attention", event.getType().isAttention());
			row.put("actor", event.getActor());
			row.put("target", event.getTarget());
			row.put("details", event.getDetails());
			row.put("ip", event.getIp());
			row.put("userAgent", event.getUserAgent());
			return row;
		}).toList();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("events", rows);
		body.put("total", result.getTotalElements());
		body.put("page", result.getNumber());
		body.put("size", result.getSize());
		return ResponseEntity.ok(body);
	}


	@Operation(summary = "Типы событий журнала безопасности")
	@GetMapping("/types")
	@PreAuthorize("@access.can('SECURITY_AUDIT_VIEW')")
	public ResponseEntity<Object> types() {
		return ResponseEntity.ok(Arrays.stream(SecurityAuditEventType.values())
				.map(type -> Map.of("code", type.name(), "label", type.getLabel(), "attention", type.isAttention()))
				.toList());
	}

}
