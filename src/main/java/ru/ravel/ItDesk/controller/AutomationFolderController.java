package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.dto.AutomationFolderDtos.FolderRequest;
import ru.ravel.ItDesk.dto.AutomationFolderDtos.LayoutRequest;
import ru.ravel.ItDesk.service.AutomationFolderService;

import java.util.Map;
import java.util.NoSuchElementException;


@RestController
@RequestMapping("/api/v1/automation")
@CrossOrigin
@RequiredArgsConstructor
@Hidden
@Tag(name = "Автоматизация", description = "Папки автоматизаций и раскладка правил по папкам.")
public class AutomationFolderController {

	private final AutomationFolderService folderService;


	@Operation(summary = "Получить папки автоматизаций")
	@GetMapping("/folders")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> folders() {
		return ResponseEntity.ok(folderService.list());
	}


	@Operation(summary = "Создать папку автоматизаций")
	@PostMapping("/folders")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> createFolder(@RequestBody FolderRequest request) {
		return guarded(() -> ResponseEntity.ok(folderService.create(request)));
	}


	@Operation(summary = "Переименовать или свернуть/развернуть папку автоматизаций")
	@PatchMapping("/folders/{folderId}")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> updateFolder(@PathVariable Long folderId, @RequestBody FolderRequest request) {
		return guarded(() -> ResponseEntity.ok(folderService.update(folderId, request)));
	}


	@Operation(summary = "Удалить папку автоматизаций", description = "Правила из папки не удаляются, а встают на верхний уровень на место папки.")
	@DeleteMapping("/folders/{folderId}")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> deleteFolder(@PathVariable Long folderId) {
		return guarded(() -> ResponseEntity.ok(folderService.delete(folderId)));
	}


	@Operation(summary = "Сохранить раскладку автоматизаций по папкам",
			description = "Порядок элементов верхнего уровня и содержимое папок; order_number выполнения пересчитывается по порядку чтения.")
	@PutMapping("/layout")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> saveLayout(@RequestBody LayoutRequest request) {
		return guarded(() -> ResponseEntity.ok(folderService.saveLayout(request)));
	}


	private ResponseEntity<Object> guarded(java.util.function.Supplier<ResponseEntity<Object>> action) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			return action.get();
		} catch (NoSuchElementException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
		}
	}
}
