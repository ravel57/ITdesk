package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.JevSettingsDto;
import ru.ravel.ItDesk.dto.JevSettingsRequest;
import ru.ravel.ItDesk.model.AppSettings;
import ru.ravel.ItDesk.model.jev.JevThreshold;
import ru.ravel.ItDesk.repository.AppSettingsRepository;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * Настройки Jev в общих настройках приложения (таблица app_settings).
 * Подключение (включение, API-ключ, URL, модель, таймаут) задаётся только в конфиге (app.jev.* / JEV_*)
 * и через UI не меняется. Пороги (по каждой автоматизации) и исключённые приоритеты задаются только в настройках (БД).
 */
@Service
@RequiredArgsConstructor
public class JevSettingsService {

	private static final Long SETTINGS_ID = 1L;
	private static final String PRIORITY_SEPARATOR = "\n";

	private final AppSettingsRepository appSettingsRepository;
	private final AppSettingsService appSettingsService;

	@Value("${app.jev.enabled:false}")
	private boolean enabled;

	@Value("${app.jev.url:https://api.typesafe.ai/v1/systemone}")
	private String url;

	@Value("${app.jev.api-key:}")
	private String apiKey;

	@Value("${app.jev.model:jev-latest}")
	private String model;

	@Value("${app.jev.request-timeout-ms:10000}")
	private int requestTimeoutMs;

	/**
	 * Итоговая конфигурация для вызовов Jev (с API-ключом). Только для внутреннего использования.
	 */
	public record JevConfig(
			boolean enabled,
			String url,
			String apiKey,
			String model,
			Map<JevThreshold, Double> thresholds,
			double replyRequiredThreshold,
			double replyNotRequiredThreshold,
			List<String> priorityExcluded,
			int requestTimeoutMs
	) {
		public boolean isUsable() {
			return enabled && apiKey != null && !apiKey.isBlank();
		}


		/**
		 * Порог уверенности автоматизации: решение Jev ниже него не применяется.
		 */
		public double threshold(JevThreshold automation) {
			Double value = thresholds == null ? null : thresholds.get(automation);
			return value == null ? automation.defaultValue() : value;
		}
	}


	/**
	 * Конфигурация для вызовов Jev. Пороги — из настроек, но заданные в выполняемом правиле
	 * (аргументы действия jev.*) перекрывают их.
	 */
	@Transactional(readOnly = true)
	public JevConfig effective() {
		AppSettings settings = appSettingsService.getGeneralSettings();
		Map<JevThreshold, Double> thresholds = JevThreshold.resolve(settings.getJevThresholds());
		double replyRequired = settings.getJevReplyRequiredThreshold();
		double replyNotRequired = settings.getJevReplyNotRequiredThreshold();
		JevThresholdOverride override = JevThresholdOverride.current();
		if (override != null) {
			thresholds.putAll(override.thresholds());
			replyRequired = override.replyRequired() == null ? replyRequired : override.replyRequired();
			replyNotRequired = override.replyNotRequired() == null ? replyNotRequired : override.replyNotRequired();
		}
		return new JevConfig(
				enabled,
				url,
				normalizedApiKey(),
				model,
				thresholds,
				replyRequired,
				replyNotRequired,
				priorityExcluded(settings),
				requestTimeoutMs
		);
	}


	@Transactional(readOnly = true)
	public JevSettingsDto get() {
		return toDto(appSettingsService.getGeneralSettings());
	}


	@Transactional
	public JevSettingsDto update(JevSettingsRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("Настройки не переданы");
		}
		appSettingsRepository.insertDefaultIfNotExists();
		AppSettings settings = appSettingsRepository.findByIdForUpdate(SETTINGS_ID)
				.orElseThrow(() -> new IllegalStateException("Не удалось загрузить настройки приложения"));

		double requiredThreshold = requireRatio(request.replyRequiredThreshold(), "Порог «ответ нужен»");
		double notRequiredThreshold = requireRatio(request.replyNotRequiredThreshold(), "Порог «ответ не нужен»");
		if (notRequiredThreshold >= requiredThreshold) {
			throw new IllegalArgumentException("Порог «ответ не нужен» должен быть меньше порога «ответ нужен»");
		}

		if (request.thresholds() != null) {
			settings.setJevThresholds(mergeThresholds(settings.getJevThresholds(), request.thresholds()));
		}
		settings.setJevReplyRequiredThreshold(requiredThreshold);
		settings.setJevReplyNotRequiredThreshold(notRequiredThreshold);
		settings.setJevPriorityExcluded(request.priorityExcluded() == null
				? ""
				: String.join(PRIORITY_SEPARATOR, cleanNames(request.priorityExcluded())));
		return toDto(appSettingsRepository.save(settings));
	}


	private JevSettingsDto toDto(AppSettings settings) {
		return new JevSettingsDto(
				enabled,
				!normalizedApiKey().isEmpty(),
				thresholds(settings),
				settings.getJevReplyRequiredThreshold(),
				settings.getJevReplyNotRequiredThreshold(),
				priorityExcluded(settings)
		);
	}


	private List<JevSettingsDto.Threshold> thresholds(AppSettings settings) {
		Map<JevThreshold, Double> values = JevThreshold.resolve(settings.getJevThresholds());
		return Arrays.stream(JevThreshold.values())
				.map(item -> new JevSettingsDto.Threshold(item.key(), item.label(), item.hint(), values.get(item)))
				.toList();
	}


	private Map<String, Double> mergeThresholds(Map<String, Double> current, Map<String, Double> changes) {
		Map<String, Double> result = new HashMap<>(current == null ? Map.of() : current);
		changes.forEach((key, value) -> {
			JevThreshold automation = JevThreshold.byKey(key)
					.orElseThrow(() -> new IllegalArgumentException("Неизвестный порог Jev: " + key));
			result.put(automation.key(), requireRatio(value, "Порог «" + automation.label() + "»"));
		});
		return result;
	}


	private List<String> priorityExcluded(AppSettings settings) {
		String stored = settings.getJevPriorityExcluded();
		return stored == null ? List.of() : cleanNames(Arrays.asList(stored.split(PRIORITY_SEPARATOR)));
	}


	private String normalizedApiKey() {
		return apiKey == null ? "" : apiKey.trim();
	}


	private double requireRatio(Double value, String label) {
		if (value == null || Double.isNaN(value) || value < 0 || value > 1) {
			throw new IllegalArgumentException(label + ": допустимо число от 0 до 1");
		}
		return value;
	}


	private List<String> cleanNames(List<String> names) {
		if (names == null) {
			return List.of();
		}
		return names.stream()
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(name -> !name.isEmpty())
				.distinct()
				.toList();
	}

}
