package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.ravel.ItDesk.dto.JevSettingsDto;
import ru.ravel.ItDesk.dto.JevSettingsRequest;
import ru.ravel.ItDesk.model.AppSettings;
import ru.ravel.ItDesk.model.jev.JevThreshold;
import ru.ravel.ItDesk.repository.AppSettingsRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JevSettingsServiceTest {

	private AppSettings stored;
	private JevSettingsService service;

	@BeforeEach
	void setUp() {
		stored = new AppSettings();
		AppSettingsRepository repository = mock(AppSettingsRepository.class);
		when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(stored));
		when(repository.save(any(AppSettings.class))).thenAnswer(invocation -> invocation.getArgument(0));
		AppSettingsService appSettingsService = mock(AppSettingsService.class);
		when(appSettingsService.getGeneralSettings()).thenAnswer(invocation -> stored);
		service = new JevSettingsService(repository, appSettingsService);
		ReflectionTestUtils.setField(service, "enabled", true);
		ReflectionTestUtils.setField(service, "apiKey", "env-key");
		ReflectionTestUtils.setField(service, "url", "https://api.typesafe.ai/v1/systemone");
		ReflectionTestUtils.setField(service, "model", "jev-latest");
		ReflectionTestUtils.setField(service, "requestTimeoutMs", 10000);
	}

	@Test
	void enabledAndKeyComeFromConfigOnly() {
		JevSettingsDto dto = service.get();
		assertTrue(dto.enabled());
		assertTrue(dto.apiKeyConfigured());
		assertFalse(dto.toString().contains("env-key"));
		assertEquals("env-key", service.effective().apiKey());
		assertTrue(service.effective().isUsable());

		ReflectionTestUtils.setField(service, "apiKey", " ");
		assertFalse(service.get().apiKeyConfigured());
		assertFalse(service.effective().isUsable());
	}

	@Test
	void updateStoresTuningAndDefaultsComeFromEntity() {
		JevSettingsService.JevConfig defaults = service.effective();
		assertEquals("jev-latest", defaults.model());
		assertEquals(List.of("Приостановленно"), defaults.priorityExcluded());

		assertEquals(0.75, defaults.threshold(JevThreshold.SPLIT_TASKS));
		assertEquals(0.6, defaults.threshold(JevThreshold.ESCALATION));

		JevSettingsDto dto = service.update(new JevSettingsRequest(
				Map.of("escalateIfNeeded", 0.7, "splitTasks", 0.9), 0.8, 0.1, List.of(" A ", "A", "B")));
		assertEquals(0.7, threshold(dto, "escalateIfNeeded"));
		assertEquals(0.9, threshold(dto, "splitTasks"));
		assertEquals(0.6, threshold(dto, "classifyTask"));
		assertEquals(0.7, service.effective().threshold(JevThreshold.ESCALATION));
		assertEquals(JevThreshold.values().length, dto.thresholds().size());
		assertEquals(List.of("A", "B"), dto.priorityExcluded());
		assertEquals(0.8, service.effective().replyRequiredThreshold());
		assertEquals(10000, service.effective().requestTimeoutMs());
		assertEquals("https://api.typesafe.ai/v1/systemone", service.effective().url());
	}

	@Test
	void ruleThresholdsOverrideSettingsOnlyInsideAction() {
		JevThresholdOverride override = new JevThresholdOverride(
				Map.of(JevThreshold.SPLIT_TASKS, 0.9), 0.8, null);
		double[] inside = new double[3];
		override.apply(() -> {
			inside[0] = service.effective().threshold(JevThreshold.SPLIT_TASKS);
			inside[1] = service.effective().threshold(JevThreshold.ESCALATION);
			inside[2] = service.effective().replyRequiredThreshold();
		});
		assertArrayEquals(new double[]{0.9, 0.6, 0.8}, inside);
		assertEquals(0.75, service.effective().threshold(JevThreshold.SPLIT_TASKS));
		assertEquals(0.6, service.effective().replyRequiredThreshold());
		assertEquals(0.25, service.effective().replyNotRequiredThreshold());
	}

	@Test
	void rejectsInvalidValues() {
		assertThrows(IllegalArgumentException.class, () -> service.update(request(0.6, 0.7)));
		assertThrows(IllegalArgumentException.class, () -> service.update(request(1.5, 0.25)));
		assertThrows(IllegalArgumentException.class, () -> service.update(request(0.6, null)));
		assertThrows(IllegalArgumentException.class, () -> service.update(
				new JevSettingsRequest(Map.of("unknown", 0.5), 0.6, 0.25, List.of())));
	}

	private JevSettingsRequest request(double escalation, Double notRequired) {
		return new JevSettingsRequest(Map.of("escalateIfNeeded", escalation), 0.6, notRequired, List.of("Приостановленно"));
	}

	private double threshold(JevSettingsDto dto, String key) {
		return dto.thresholds().stream().filter(item -> item.key().equals(key)).findFirst().orElseThrow().value();
	}
}
