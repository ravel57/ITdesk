package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.SlaZonesDto;
import ru.ravel.ItDesk.model.AppSettings;
import ru.ravel.ItDesk.model.ObserverFeature;
import ru.ravel.ItDesk.repository.AppSettingsRepository;

import java.security.SecureRandom;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppSettingsService {

	private static final Long SETTINGS_ID = 1L;

	private static final long OBSERVER_FEATURES_CACHE_MS = 10_000;

	private final AppSettingsRepository appSettingsRepository;

	/** Права проверяются на каждый запрос: разделы наблюдателя кэшируются ненадолго и сбрасываются при сохранении. */
	private volatile Set<ObserverFeature> observerFeaturesCache;
	private volatile long observerFeaturesLoadedAt;


	@Transactional(readOnly = true)
	public AppSettings getGeneralSettings() {
		return getOrCreate();
	}


	@Transactional(readOnly = true)
	public Set<ObserverFeature> getObserverFeatures() {
		Set<ObserverFeature> cached = observerFeaturesCache;
		if (cached != null && System.currentTimeMillis() - observerFeaturesLoadedAt < OBSERVER_FEATURES_CACHE_MS) {
			return cached;
		}
		Set<ObserverFeature> loaded = parseObserverFeatures(getOrCreate().getObserverFeatures());
		observerFeaturesCache = loaded;
		observerFeaturesLoadedAt = System.currentTimeMillis();
		return loaded;
	}


	@Transactional
	public Set<ObserverFeature> updateObserverFeatures(Collection<ObserverFeature> features) {
		Set<ObserverFeature> enabled = features == null || features.isEmpty()
				? EnumSet.noneOf(ObserverFeature.class)
				: EnumSet.copyOf(features.stream().filter(Objects::nonNull).toList());
		AppSettings settings = getOrCreate();
		settings.setObserverFeatures(enabled.stream().map(Enum::name)
				.collect(Collectors.toCollection(java.util.LinkedHashSet::new)));
		appSettingsRepository.save(settings);
		observerFeaturesCache = null;
		return enabled;
	}


	private static Set<ObserverFeature> parseObserverFeatures(Collection<String> names) {
		Set<ObserverFeature> result = EnumSet.noneOf(ObserverFeature.class);
		if (names == null) {
			return EnumSet.allOf(ObserverFeature.class);
		}
		for (String name : names) {
			try {
				result.add(ObserverFeature.valueOf(name));
			} catch (IllegalArgumentException | NullPointerException ignored) {
				// Неизвестный раздел (например, после отката версии) пропускаем
			}
		}
		return result;
	}


	@Transactional
	public boolean updateMfaRequiredForAdmins(boolean required) {
		AppSettings settings = getOrCreate();
		settings.setMfaRequiredForAdmins(required);
		appSettingsRepository.save(settings);
		return required;
	}


	@Transactional
	public String getOrCreateJwtSigningSecret() {
		appSettingsRepository.insertDefaultIfNotExists();
		AppSettings settings = appSettingsRepository.findByIdForUpdate(SETTINGS_ID)
				.orElseThrow(() -> new IllegalStateException("Не удалось загрузить настройки приложения"));
		String existing = settings.getJwtSigningSecret();
		if (existing != null && !existing.isBlank()) {
			return existing;
		}

		byte[] secretBytes = new byte[64];
		new SecureRandom().nextBytes(secretBytes);
		String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
		settings.setJwtSigningSecret(generated);
		appSettingsRepository.save(settings);
		return generated;
	}


	/** Границы оранжевой и красной зон SLA (общие для всех SLA). */
	@Transactional(readOnly = true)
	public SlaZonesDto getSlaZones() {
		AppSettings settings = getOrCreate();
		return SlaZonesDto.of(settings.getSlaOrangeStartPercent(), settings.getSlaRedStartPercent());
	}


	@Transactional
	public AppSettings updateGeneralSettings(AppSettings payload) {
		if (payload == null) {
			throw new IllegalArgumentException("Настройки не переданы");
		}
		validateTimezone(payload.getTimezone());
		validateTime(payload.getWorkdayStart(), "Начало рабочего дня");
		validateTime(payload.getWorkdayEnd(), "Конец рабочего дня");
		LocalTime start = LocalTime.parse(payload.getWorkdayStart());
		LocalTime end = LocalTime.parse(payload.getWorkdayEnd());
		if (!end.isAfter(start)) {
			throw new IllegalArgumentException("Конец рабочего дня должен быть позже начала");
		}
		if (Boolean.TRUE.equals(payload.getWorkingTimeEnabled()) && !hasAnyWorkingDay(payload)) {
			throw new IllegalArgumentException("Выберите хотя бы один рабочий день");
		}
		AppSettings settings = getOrCreate();
		settings.setTimezone(payload.getTimezone());
		settings.setWorkingTimeEnabled(Boolean.TRUE.equals(payload.getWorkingTimeEnabled()));
		settings.setWorkdayStart(payload.getWorkdayStart());
		settings.setWorkdayEnd(payload.getWorkdayEnd());
		settings.setMondayEnabled(Boolean.TRUE.equals(payload.getMondayEnabled()));
		settings.setTuesdayEnabled(Boolean.TRUE.equals(payload.getTuesdayEnabled()));
		settings.setWednesdayEnabled(Boolean.TRUE.equals(payload.getWednesdayEnabled()));
		settings.setThursdayEnabled(Boolean.TRUE.equals(payload.getThursdayEnabled()));
		settings.setFridayEnabled(Boolean.TRUE.equals(payload.getFridayEnabled()));
		settings.setSaturdayEnabled(Boolean.TRUE.equals(payload.getSaturdayEnabled()));
		settings.setSundayEnabled(Boolean.TRUE.equals(payload.getSundayEnabled()));
		// Клиенты старой версии поле не присылают: тогда оставляем сохранённое значение
		if (payload.getAnswerWaitingWorkingTimeEnabled() != null) {
			settings.setAnswerWaitingWorkingTimeEnabled(payload.getAnswerWaitingWorkingTimeEnabled());
		}
		// Клиенты старой версии зоны SLA не присылают: тогда оставляем сохранённые
		if (payload.getSlaOrangeStartPercent() != null || payload.getSlaRedStartPercent() != null) {
			Integer orange = payload.getSlaOrangeStartPercent() != null ? payload.getSlaOrangeStartPercent() : settings.getSlaOrangeStartPercent();
			Integer red = payload.getSlaRedStartPercent() != null ? payload.getSlaRedStartPercent() : settings.getSlaRedStartPercent();
			if (!SlaZonesDto.isValid(orange, red)) {
				throw new IllegalArgumentException("Зоны SLA: начало красной зоны должно быть меньше начала оранжевой (от 1% до 99%)");
			}
			settings.setSlaOrangeStartPercent(orange);
			settings.setSlaRedStartPercent(red);
		}
		settings.setSupportLineAccessMode(
			payload.getSupportLineAccessMode() == null
					? ru.ravel.ItDesk.model.SupportLineAccessMode.HYBRID
					: payload.getSupportLineAccessMode()
		);
		return appSettingsRepository.save(settings);
	}


	private AppSettings getOrCreate() {
		return appSettingsRepository.findById(SETTINGS_ID)
				.orElseGet(() -> {
					appSettingsRepository.insertDefaultIfNotExists();
					return appSettingsRepository.findById(SETTINGS_ID)
							.orElseThrow(() -> new IllegalStateException("Не удалось создать общие настройки приложения"));
				});
	}


	private boolean hasAnyWorkingDay(AppSettings settings) {
		return Boolean.TRUE.equals(settings.getMondayEnabled())
				|| Boolean.TRUE.equals(settings.getTuesdayEnabled())
				|| Boolean.TRUE.equals(settings.getWednesdayEnabled())
				|| Boolean.TRUE.equals(settings.getThursdayEnabled())
				|| Boolean.TRUE.equals(settings.getFridayEnabled())
				|| Boolean.TRUE.equals(settings.getSaturdayEnabled())
				|| Boolean.TRUE.equals(settings.getSundayEnabled());
	}


	private void validateTimezone(String timezone) {
		if (timezone == null || timezone.isBlank()) {
			throw new IllegalArgumentException("Часовой пояс не указан");
		}
		if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
			throw new IllegalArgumentException("Некорректный часовой пояс: " + timezone);
		}
	}


	private void validateTime(String value, String label) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(label + " не указано");
		}
		try {
			LocalTime.parse(value);
		} catch (Exception e) {
			throw new IllegalArgumentException(label + " должен быть в формате HH:mm");
		}
	}

}