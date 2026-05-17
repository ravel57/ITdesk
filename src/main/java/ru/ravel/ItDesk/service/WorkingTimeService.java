package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.AppSettings;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;


@Service
@RequiredArgsConstructor
public class WorkingTimeService {

	private final AppSettingsService appSettingsService;


	public WorkingTimeState getCurrentState() {
		AppSettings settings = appSettingsService.getGeneralSettings();
		boolean enabled = Boolean.TRUE.equals(settings.getWorkingTimeEnabled());
		if (!enabled) {
			return new WorkingTimeState(false, true);
		}
		ZoneId zoneId = ZoneId.of(Objects.requireNonNullElse(settings.getTimezone(), "UTC"));
		ZonedDateTime now = ZonedDateTime.now(zoneId);
		return new WorkingTimeState(true, isWorkingAt(now, settings));
	}


	public boolean isWorkingNow() {
		return getCurrentState().workingNow();
	}

	public boolean isWorkingAt(ZonedDateTime dateTime, AppSettings settings) {
		if (dateTime == null || settings == null) {
			return true;
		}
		if (!Boolean.TRUE.equals(settings.getWorkingTimeEnabled())) {
			return true;
		}
		if (!isDayEnabled(dateTime.getDayOfWeek(), settings)) {
			return false;
		}
		LocalTime start = LocalTime.parse(Objects.requireNonNullElse(settings.getWorkdayStart(), "09:00"));
		LocalTime end = LocalTime.parse(Objects.requireNonNullElse(settings.getWorkdayEnd(), "18:00"));
		LocalTime current = dateTime.toLocalTime();
		return !current.isBefore(start) && current.isBefore(end);
	}


	private boolean isDayEnabled(DayOfWeek dayOfWeek, AppSettings settings) {
		return switch (dayOfWeek) {
			case MONDAY -> Boolean.TRUE.equals(settings.getMondayEnabled());
			case TUESDAY -> Boolean.TRUE.equals(settings.getTuesdayEnabled());
			case WEDNESDAY -> Boolean.TRUE.equals(settings.getWednesdayEnabled());
			case THURSDAY -> Boolean.TRUE.equals(settings.getThursdayEnabled());
			case FRIDAY -> Boolean.TRUE.equals(settings.getFridayEnabled());
			case SATURDAY -> Boolean.TRUE.equals(settings.getSaturdayEnabled());
			case SUNDAY -> Boolean.TRUE.equals(settings.getSundayEnabled());
		};
	}


	public record WorkingTimeState(boolean enabled, boolean workingNow) {
	}

}