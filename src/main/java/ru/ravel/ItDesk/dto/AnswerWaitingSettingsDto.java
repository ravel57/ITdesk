package ru.ravel.ItDesk.dto;

import java.util.List;


/**
 * Как считать время «Ожидает ответа»: календарное или рабочее. Отдаётся всем, кто видит чаты
 * (общие настройки доступны не всем ролям), чтобы список чатов считал таймер тем же алгоритмом, что и сервер.
 * {@code workingTimeUsed} — итоговый флаг: настройка включена и рабочее время компании включено.
 * {@code workingDays} — номера дней по ISO (1 — понедельник … 7 — воскресенье).
 */
public record AnswerWaitingSettingsDto(
		boolean workingTimeUsed,
		String timezone,
		String workdayStart,
		String workdayEnd,
		List<Integer> workingDays
) {
}
