package ru.ravel.ItDesk.service;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import ru.ravel.ItDesk.dto.PasswordResetConfirm;
import ru.ravel.ItDesk.dto.PasswordResetLinkRequest;
import ru.ravel.ItDesk.feign.SupportFeignClient;
import ru.ravel.ItDesk.model.License;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.LicenseRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


class PasswordResetServiceTest {

	private final UUID license = UUID.randomUUID();
	private SupportFeignClient supportFeignClient;
	private UserRepository userRepository;
	private PasswordEncoder passwordEncoder;
	private SessionRegistry sessionRegistry;
	private PasswordResetService service;
	private User ivan;


	@BeforeEach
	void setUp() {
		supportFeignClient = mock(SupportFeignClient.class);
		LicenseRepository licenseRepository = mock(LicenseRepository.class);
		userRepository = mock(UserRepository.class);
		passwordEncoder = mock(PasswordEncoder.class);
		sessionRegistry = mock(SessionRegistry.class);
		service = new PasswordResetService(supportFeignClient, licenseRepository, userRepository, passwordEncoder, sessionRegistry,
				mock(SecurityAuditService.class));
		ReflectionTestUtils.setField(service, "publicUrl", "");
		ReflectionTestUtils.setField(service, "allowedOrigins", "https://desk.example.ru/, http://localhost:8080");

		License instance = new License();
		instance.setLicense(license);
		when(licenseRepository.findAll()).thenReturn(List.of(instance));
		ivan = User.builder().id(5L).username("Ivan@Example.ru").password("old").authorities(List.of(Role.OPERATOR)).build();
		when(userRepository.findFirstByUsernameIgnoreCase(any())).thenAnswer(invocation ->
				"ivan@example.ru".equalsIgnoreCase(invocation.getArgument(0)) ? Optional.of(ivan) : Optional.empty());
		when(passwordEncoder.encode(any())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
	}


	@Test
	void requestAsksLicenseServerForLinkToThisInstanceWithoutChangingPassword() {
		service.requestLink("ivan@example.ru");

		verify(supportFeignClient).requestPasswordResetLink(license, new PasswordResetLinkRequest("Ivan@Example.ru", "https://desk.example.ru"));
		assertEquals("old", ivan.getPassword());
		verify(userRepository, never()).save(any());
	}


	@Test
	void unknownOrDisabledUserGetsNoLink() {
		service.requestLink("nobody@example.ru");
		ivan.setIsEnabled(false);
		service.requestLink("ivan@example.ru");

		verifyNoInteractions(supportFeignClient);
	}


	@Test
	void publicUrlOverridesAllowedOrigin() {
		ReflectionTestUtils.setField(service, "publicUrl", "https://uldesk.client.ru/");
		assertEquals("https://uldesk.client.ru", service.baseUrl());
	}


	@Test
	void completeConsumesLinkSetsPasswordAndEndsSessions() {
		when(supportFeignClient.consumePasswordResetLink(eq(license), any())).thenReturn(Map.of("email", "ivan@example.ru"));
		SessionInformation session = new SessionInformation(ivan, "s1", new Date());
		when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(ivan));
		when(sessionRegistry.getAllSessions(ivan, false)).thenReturn(List.of(session));

		service.complete(" token-1 ", "new-password-1");

		verify(supportFeignClient).consumePasswordResetLink(license, new PasswordResetConfirm("token-1", null));
		assertEquals("hash:new-password-1", ivan.getPassword());
		verify(userRepository).save(ivan);
		assertTrue(session.isExpired());
	}


	@Test
	void weakPasswordDoesNotBurnTheLink() {
		assertThrows(IllegalArgumentException.class, () -> service.complete("token-1", "short"));
		verifyNoInteractions(supportFeignClient);
	}


	@Test
	void usedOrExpiredLinkIsRejected() {
		when(supportFeignClient.consumePasswordResetLink(eq(license), any())).thenThrow(gone());
		when(supportFeignClient.verifyPasswordResetLink(eq(license), any())).thenThrow(gone());

		assertThrows(IllegalStateException.class, () -> service.complete("token-1", "new-password-1"));
		assertEquals(PasswordResetService.LinkState.INVALID, service.verify("token-1"));
		assertEquals(PasswordResetService.LinkState.INVALID, service.verify(" "));
		assertEquals("old", ivan.getPassword());
	}


	@Test
	void validLinkIsReportedWithoutConsuming() {
		when(supportFeignClient.verifyPasswordResetLink(eq(license), any())).thenReturn(Map.of("expiresAt", "2026-10-18T13:00:00Z"));

		assertEquals(PasswordResetService.LinkState.VALID, service.verify("token-1"));
		verify(supportFeignClient, never()).consumePasswordResetLink(any(), any());
	}


	private static FeignException gone() {
		Request request = Request.create(Request.HttpMethod.POST, "https://admin.uldesk.ru", Map.of(), null, StandardCharsets.UTF_8, null);
		return new FeignException.Gone("gone", request, null, Map.of());
	}

}
