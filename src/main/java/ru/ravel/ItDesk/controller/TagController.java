package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.service.TagService;

import java.util.List;


@RestController
@RequestMapping("/api/v1")
@CrossOrigin
@RequiredArgsConstructor
@Tag(name = "Теги", description = "Справочник тегов и порядок отображения.")
public class TagController {

	private final TagService tagService;


	@Operation(summary = "Получить теги", description = "Получить теги. Endpoint GET /api/v1/tags; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/tags")
	@PreAuthorize("@access.can('REFERENCE_VIEW')")
	public ResponseEntity<Object> getTags() {
		return ResponseEntity.ok().body(tagService.getTags());
	}


	@Operation(summary = "Создать тег", description = "Создать тег. Endpoint POST /api/v1/tag; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/tag")
	@PreAuthorize("@access.can('REFERENCE_MANAGE')")
	public ResponseEntity<Object> newTag(@RequestBody ru.ravel.ItDesk.model.Tag tag) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(tagService.newTag(tag));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Обновить тег", description = "Обновить тег. Endpoint PATCH /api/v1/tag; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/tag")
	@PreAuthorize("@access.can('REFERENCE_MANAGE')")
	public ResponseEntity<Object> updateTag(@RequestBody ru.ravel.ItDesk.model.Tag tag) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(tagService.updateTag(tag));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Удалить тег", description = "Удалить тег. Endpoint DELETE /api/v1/tag/{tagId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/tag/{tagId}")
	@PreAuthorize("@access.can('REFERENCE_MANAGE')")
	public ResponseEntity<Object> deleteTag(@PathVariable Long tagId) {
		if (LicenseStarter.isLicenseActive) {
			tagService.deleteTag(tagId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Изменить порядок тегов", description = "Изменить порядок тегов. Endpoint PATCH /api/v1/tags/resort; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/tags/resort")
	@PreAuthorize("@access.can('REFERENCE_MANAGE')")
	public ResponseEntity<Object> resortTags(@RequestBody List<ru.ravel.ItDesk.model.Tag> tags) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(tagService.resortTags(tags));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}
}