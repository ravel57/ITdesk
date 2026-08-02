package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.AppSettings;
import ru.ravel.ItDesk.model.OlaUnit;
import ru.ravel.ItDesk.model.SupportLine;

import java.time.*;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class OlaWorkingTimeService {

    private final AppSettingsService appSettingsService;

    public long durationSeconds(SupportLine line) {
        if (line == null || !Boolean.TRUE.equals(line.getOlaEnabled())) {
            return 0L;
        }
        int value = Math.max(0, Objects.requireNonNullElse(line.getOlaValue(), 0));
        if (value == 0) {
            return 0L;
        }
        OlaUnit unit = Objects.requireNonNullElse(line.getOlaUnit(), OlaUnit.HOURS);
        return switch (unit) {
            case MINUTES -> Math.multiplyExact((long) value, 60L);
            case HOURS -> Math.multiplyExact((long) value, 3600L);
            case WORKING_DAYS -> Math.multiplyExact((long) value, getSettings().getWorkdayDuration().getSeconds());
        };
    }

    public ZonedDateTime addSeconds(ZonedDateTime start, long seconds, boolean useWorkingTime) {
        if (start == null) {
            return null;
        }
        if (seconds <= 0) {
            return start;
        }
        AppSettings settings = getSettings();
        if (!useWorkingTime || !Boolean.TRUE.equals(settings.getWorkingTimeEnabled())) {
            return start.plusSeconds(seconds);
        }

        ZoneId zone = zone(settings);
        ZonedDateTime cursor = start.withZoneSameInstant(zone);
        long remaining = seconds;
        int guard = 0;
        while (remaining > 0 && guard++ < 5000) {
            cursor = normalizeToWorkingMoment(cursor, settings);
            ZonedDateTime endOfWindow = cursor.toLocalDate().atTime(LocalTime.parse(settings.getWorkdayEnd())).atZone(zone);
            long available = Math.max(0L, Duration.between(cursor, endOfWindow).getSeconds());
            if (available >= remaining) {
                return cursor.plusSeconds(remaining).withZoneSameInstant(start.getZone());
            }
            remaining -= available;
            cursor = nextWorkingStart(cursor.toLocalDate().plusDays(1), settings);
        }
        throw new IllegalStateException("Не удалось рассчитать срок OLA по рабочему календарю");
    }

    public long secondsBetween(ZonedDateTime from, ZonedDateTime to, boolean useWorkingTime) {
        if (from == null || to == null) {
            return 0L;
        }
        if (to.isBefore(from)) {
            return -secondsBetween(to, from, useWorkingTime);
        }
        AppSettings settings = getSettings();
        if (!useWorkingTime || !Boolean.TRUE.equals(settings.getWorkingTimeEnabled())) {
            return Duration.between(from, to).getSeconds();
        }

        ZoneId zone = zone(settings);
        ZonedDateTime start = from.withZoneSameInstant(zone);
        ZonedDateTime end = to.withZoneSameInstant(zone);
        LocalDate date = start.toLocalDate();
        LocalDate endDate = end.toLocalDate();
        long total = 0L;
        int guard = 0;
        while (!date.isAfter(endDate) && guard++ < 5000) {
            if (isWorkingDay(date.getDayOfWeek(), settings)) {
                ZonedDateTime windowStart = date.atTime(LocalTime.parse(settings.getWorkdayStart())).atZone(zone);
                ZonedDateTime windowEnd = date.atTime(LocalTime.parse(settings.getWorkdayEnd())).atZone(zone);
                ZonedDateTime segmentStart = start.isAfter(windowStart) ? start : windowStart;
                ZonedDateTime segmentEnd = end.isBefore(windowEnd) ? end : windowEnd;
                if (segmentEnd.isAfter(segmentStart)) {
                    total += Duration.between(segmentStart, segmentEnd).getSeconds();
                }
            }
            date = date.plusDays(1);
        }
        return total;
    }

    public AppSettings getSettings() {
        return appSettingsService.getGeneralSettings();
    }

    private ZonedDateTime normalizeToWorkingMoment(ZonedDateTime value, AppSettings settings) {
        ZoneId zone = zone(settings);
        ZonedDateTime cursor = value.withZoneSameInstant(zone);
        LocalTime start = LocalTime.parse(settings.getWorkdayStart());
        LocalTime end = LocalTime.parse(settings.getWorkdayEnd());
        if (!isWorkingDay(cursor.getDayOfWeek(), settings)) {
            return nextWorkingStart(cursor.toLocalDate().plusDays(1), settings);
        }
        if (cursor.toLocalTime().isBefore(start)) {
            return cursor.toLocalDate().atTime(start).atZone(zone);
        }
        if (!cursor.toLocalTime().isBefore(end)) {
            return nextWorkingStart(cursor.toLocalDate().plusDays(1), settings);
        }
        return cursor;
    }

    private ZonedDateTime nextWorkingStart(LocalDate date, AppSettings settings) {
        ZoneId zone = zone(settings);
        LocalDate cursor = date;
        int guard = 0;
        while (!isWorkingDay(cursor.getDayOfWeek(), settings) && guard++ < 370) {
            cursor = cursor.plusDays(1);
        }
        if (!isWorkingDay(cursor.getDayOfWeek(), settings)) {
            throw new IllegalStateException("В рабочем календаре не выбран ни один рабочий день");
        }
        return cursor.atTime(LocalTime.parse(settings.getWorkdayStart())).atZone(zone);
    }

    private ZoneId zone(AppSettings settings) {
        try {
            return ZoneId.of(settings.getTimezone());
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }

    private boolean isWorkingDay(DayOfWeek day, AppSettings settings) {
        return switch (day) {
            case MONDAY -> Boolean.TRUE.equals(settings.getMondayEnabled());
            case TUESDAY -> Boolean.TRUE.equals(settings.getTuesdayEnabled());
            case WEDNESDAY -> Boolean.TRUE.equals(settings.getWednesdayEnabled());
            case THURSDAY -> Boolean.TRUE.equals(settings.getThursdayEnabled());
            case FRIDAY -> Boolean.TRUE.equals(settings.getFridayEnabled());
            case SATURDAY -> Boolean.TRUE.equals(settings.getSaturdayEnabled());
            case SUNDAY -> Boolean.TRUE.equals(settings.getSundayEnabled());
        };
    }
}
