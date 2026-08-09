package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.dto.ClientPortalMessageRequest;
import ru.ravel.ItDesk.service.ClientPortalService;

@RestController
@RequestMapping("/api/v1/portal")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CLIENT')")
public class ClientPortalController {

	private final ClientPortalService clientPortalService;

	@GetMapping("/profile")
	public ResponseEntity<Object> profile() {
		return ResponseEntity.ok(clientPortalService.getProfile());
	}

	@GetMapping("/messages")
	public ResponseEntity<Object> messages(@RequestParam(defaultValue = "1") Integer page) {
		return ResponseEntity.ok(clientPortalService.getMessages(page));
	}

	@PostMapping("/messages")
	public ResponseEntity<Object> sendMessage(@RequestBody ClientPortalMessageRequest request) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			return ResponseEntity.ok(clientPortalService.sendMessage(request));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
		}
	}
}
