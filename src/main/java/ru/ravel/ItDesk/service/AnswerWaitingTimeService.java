package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.AnswerWaitingSettingsDto;
import ru.ravel.ItDesk.model.AppSettings;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Время «Ожидает ответа»: сколько клиент ждёт ответа оператора.
 * Настройка «Учитывать рабочее время в «Ожидает ответа»» переключает календарный отсчёт на рабочий
 * (по общим настройкам рабочего времени: часовой пояс, начало/конец дня, рабочие дни).
 * Если рабочее время компании выключено, настройка ни на что не влияет — отсчёт календарный.
 */
@Service
@RequiredArgsConstructor
public class AnswerWaitingTimeService {

	private final AppSettingsService appSettingsService;
	private final OlaWorkingTimeService workingTimeService;


	public boolean isWorkingTimeUsed() {
		return isWorkingTimeUsed(appSettingsService.getGeneralSettings());
	}


	/** Секунды ожидания с момента {@code since} до {@code now}; не меньше нуля. */
	public long waitedSeconds(ZonedDateTime since, ZonedDateTime now) {
		if (since == null || now == null || !now.isAfter(since)) {
			return 0L;
		}
		return Math.max(0L, workingTimeService.secondsBetween(since, now, isWorkingTimeUsed()));
	}


	public AnswerWaitingSettingsDto getSettings() {
		AppSettings settings = appSettingsService.getGeneralSettings();
		List<Integer> days = new ArrayList<>();
		addDay(days, DayOfWeek.MONDAY, settings.getMondayEnabled());
		addDay(days, DayOfWeek.TUESDAY, settings.getTuesdayEnabled());
		addDay(days, DayOfWeek.WEDNESDAY, settings.getWednesdayEnabled());
		addDay(days, DayOfWeek.THURSDAY, settings.getThursdayEnabled());
		addDay(days, DayOfWeek.FRIDAY, settings.getFridayEnabled());
		addDay(days, DayOfWeek.SATURDAY, settings.getSaturdayEnabled());
		addDay(days, DayOfWeek.SUNDAY, settings.getSundayEnabled());
		return new AnswerWaitingSettingsDto(
				isWorkingTimeUsed(settings),
				settings.getTimezone(),
				settings.getWorkdayStart(),
				settings.getWorkdayEnd(),
				days
		);
	}


	private static void addDay(List<Integer> days, DayOfWeek day, Boolean enabled) {
		if (Boolean.TRUE.equals(enabled)) {
			days.add(day.getValue());
		}
	}


	static boolean isWorkingTimeUsed(AppSettings settings) {
		return settings != null
				&& Boolean.TRUE.equals(settings.getAnswerWaitingWorkingTimeEnabled())
				&& Boolean.TRUE.equals(settings.getWorkingTimeEnabled());
	}

}
