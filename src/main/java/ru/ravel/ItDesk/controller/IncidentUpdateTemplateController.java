package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.model.incident.IncidentUpdateTemplate;
import ru.ravel.ItDesk.service.IncidentUpdateTemplateService;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;


@RestController
@RequestMapping("/api/v1")
@CrossOrigin
@RequiredArgsConstructor
@Tag(name = "Шаблоны обновлений инцидентов", description = "Справочник шаблонов текста для записей таймлайна инцидента.")
public class IncidentUpdateTemplateController {

	private final IncidentUpdateTemplateService templateService;


	@Operation(summary = "Создать шаблон обновления инцидента", description = "Создать шаблон обновления инцидента. Endpoint POST /api/v1/incident-update-template.")
	@PostMapping("/incident-update-template")
	@PreAuthorize("@access.can('INCIDENT_TEMPLATE_MANAGE')")
	public ResponseEntity<Object> createTemplate(@RequestBody IncidentUpdateTemplate template) {
		return templateResponse(() -> templateService.create(template));
	}


	@Operation(summary = "Обновить шаблон обновления инцидента", description = "Обновить шаблон обновления инцидента. Endpoint PATCH /api/v1/incident-update-template/{templateId}.")
	@PatchMapping("/incident-update-template/{templateId}")
	@PreAuthorize("@access.can('INCIDENT_TEMPLATE_MANAGE')")
	public ResponseEntity<Object> updateTemplate(@PathVariable Long templateId, @RequestBody IncidentUpdateTemplate template) {
		return templateResponse(() -> templateService.update(templateId, template));
	}


	@Operation(summary = "Удалить шаблон обновления инцидента", description = "Удалить шаблон обновления инцидента. Endpoint DELETE /api/v1/incident-update-template/{templateId}.")
	@DeleteMapping("/incident-update-template/{templateId}")
	@PreAuthorize("@access.can('INCIDENT_TEMPLATE_MANAGE')")
	public ResponseEntity<Object> deleteTemplate(@PathVariable Long templateId) {
		return templateResponse(() -> {
			templateService.delete(templateId);
			return Map.of("success", true);
		});
	}


	@Operation(summary = "Изменить порядок шаблонов обновлений инцидента", description = "Изменить порядок шаблонов обновлений инцидента. Endpoint PATCH /api/v1/incident-update-templates/resort.")
	@PatchMapping("/incident-update-templates/resort")
	@PreAuthorize("@access.can('INCIDENT_TEMPLATE_MANAGE')")
	public ResponseEntity<Object> resortTemplates(@RequestBody List<Long> orderedIds) {
		return templateResponse(() -> templateService.resort(orderedIds));
	}


	private ResponseEntity<Object> templateResponse(Supplier<Object> operation) {
		try {
			return ResponseEntity.ok(operation.get());
		} catch (IllegalArgumentException | IllegalStateException exception) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", Objects.toString(exception.getMessage(), "Операция не выполнена")));
		}
	}
}
