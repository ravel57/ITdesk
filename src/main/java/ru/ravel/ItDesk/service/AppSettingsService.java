package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.AppSettings;
import ru.ravel.ItDesk.repository.AppSettingsRepository;

import java.time.LocalTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class AppSettingsService {

	private static final Long SETTINGS_ID = 1L;

	private final AppSettingsRepository appSettingsRepository;


	@Transactional(readOnly = true)
	public AppSettings getGeneralSettings() {
		return getOrCreate();
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