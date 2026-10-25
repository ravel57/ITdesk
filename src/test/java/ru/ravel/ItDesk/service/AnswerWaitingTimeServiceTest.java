package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.dto.AnswerRequired;
import ru.ravel.ItDesk.dto.AnswerWaitingSettingsDto;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * «Ожидает ответа»: календарное время по умолчанию, рабочее — по настройке «Учитывать рабочее время в «Ожидает ответа»».
 * Рабочий календарь в тестах: Europe/Moscow, пн–пт 09:00–18:00.
 * 2026-10-19 — понедельник, 2026-10-23 — пятница, 2026-10-24/25 — выходные.
 */
class AnswerWaitingTimeServiceTest {

	private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

	private AppSettings settings;
	private AnswerWaitingTimeService service;


	@BeforeEach
	void setUp() {
		settings = AppSettings.builder()
				.timezone("Europe/Moscow")
				.workingTimeEnabled(true)
				.workdayStart("09:00")
				.workdayEnd("18:00")
				.answerWaitingWorkingTimeEnabled(true)
				.build();
		AppSettingsService appSettingsService = mock(AppSettingsService.class);
		when(appSettingsService.getGeneralSettings()).thenAnswer(inv -> settings);
		service = new AnswerWaitingTimeService(appSettingsService, new OlaWorkingTimeService(appSettingsService));
	}


	@Test
	void defaultIsCalendarTime() {
		assertFalse(AppSettings.builder().build().getAnswerWaitingWorkingTimeEnabled(),
				"по умолчанию настройка выключена — у существующих установок поведение не меняется");
		settings.setAnswerWaitingWorkingTimeEnabled(false);
		assertEquals(Duration.ofHours(65), waited(at(23, 17, 0), at(26, 10, 0)));
	}


	@Test
	void companyWorkingTimeDisabledMeansCalendarTime() {
		settings.setWorkingTimeEnabled(false);
		assertFalse(service.isWorkingTimeUsed());
		assertEquals(Duration.ofHours(65), waited(at(23, 17, 0), at(26, 10, 0)));
	}


	@Test
	void insideWorkingHoursEqualsCalendar() {
		assertEquals(Duration.ofMinutes(150), waited(at(19, 10, 0), at(19, 12, 30)));
	}


	@Test
	void messageAtNightWaitsFromWorkdayStart() {
		// 23:00 понедельника: ночь не считается, во вторник ждём только с 09:00
		assertEquals(Duration.ofHours(1), waited(at(19, 23, 0), at(20, 10, 0)));
		assertEquals(Duration.ZERO, waited(at(19, 23, 0), at(20, 8, 59)));
		// 07:00 того же дня до начала рабочего дня
		assertEquals(Duration.ofMinutes(30), waited(at(20, 7, 0), at(20, 9, 30)));
	}


	@Test
	void messageOnWeekendWaitsFromMonday() {
		assertEquals(Duration.ofHours(1), waited(at(24, 12, 0), at(26, 10, 0)));
		assertEquals(Duration.ZERO, waited(at(25, 12, 0), at(25, 20, 0)));
	}


	@Test
	void waitingCrossesWorkdayBoundary() {
		// пятница 17:30 → понедельник 09:30: 30 мин в пятницу + 30 мин в понедельник
		assertEquals(Duration.ofHours(1), waited(at(23, 17, 30), at(26, 9, 30)));
		// пн 17:00 → ср 10:00: 1 ч + 9 ч + 1 ч
		assertEquals(Duration.ofHours(11), waited(at(19, 17, 0), at(21, 10, 0)));
	}


	@Test
	void workdayBoundariesAreHalfOpen() {
		assertEquals(Duration.ZERO, waited(at(19, 18, 0), at(19, 20, 0)));
		assertEquals(Duration.ofHours(9), waited(at(19, 9, 0), at(19, 18, 0)));
	}


	@Test
	void respectsCompanyTimezoneNotUtc() {
		// 06:00 UTC = 09:00 МСК: рабочий день только начался
		ZonedDateTime since = ZonedDateTime.of(2026, 10, 19, 6, 0, 0, 0, ZoneId.of("UTC"));
		assertEquals(Duration.ofHours(1), waited(since, since.plusHours(1)));
		// 05:00 UTC = 08:00 МСК: ещё не рабочее время
		ZonedDateTime early = since.minusHours(1);
		assertEquals(Duration.ZERO, waited(early, early.plusMinutes(30)));
	}


	@Test
	void respectsDisabledDays() {
		settings.setFridayEnabled(false);
		// пятница нерабочая: с пятницы 12:00 до понедельника 10:00 — только час понедельника
		assertEquals(Duration.ofHours(1), waited(at(23, 12, 0), at(26, 10, 0)));
	}


	@Test
	void nonPositiveWaitIsZero() {
		assertEquals(0L, service.waitedSeconds(at(19, 12, 0), at(19, 12, 0)));
		assertEquals(0L, service.waitedSeconds(at(19, 12, 0), at(19, 11, 0)));
		assertEquals(0L, service.waitedSeconds(null, at(19, 11, 0)));
	}


	@Test
	void settingsDtoDescribesCalendar() {
		AnswerWaitingSettingsDto dto = service.getSettings();
		assertTrue(dto.workingTimeUsed());
		assertEquals("Europe/Moscow", dto.timezone());
		assertEquals("09:00", dto.workdayStart());
		assertEquals("18:00", dto.workdayEnd());
		assertEquals(List.of(1, 2, 3, 4, 5), dto.workingDays());
		settings.setAnswerWaitingWorkingTimeEnabled(false);
		assertFalse(service.getSettings().workingTimeUsed());
		settings.setAnswerWaitingWorkingTimeEnabled(true);
		settings.setWorkingTimeEnabled(false);
		assertFalse(service.getSettings().workingTimeUsed());
	}


	// --- уведомление «чат без ответа слишком долго» ---

	@Test
	void unansweredNotificationUsesWorkingTimeWhenEnabled() {
		Fixture f = new Fixture();
		// клиент написал в пятницу 17:30; в понедельник 09:20 рабочего ожидания 50 мин < 60
		f.check(at(26, 9, 20));
		verify(f.webSocketService, never()).userNotification(any(), any());
		// в понедельник 09:30 — ровно час (30 + 30 мин): уведомление уходит
		f.check(at(26, 9, 30));
		verify(f.webSocketService, times(1)).userNotification(eq("operator"), any());
	}


	@Test
	void unansweredNotificationUsesCalendarTimeWhenDisabled() {
		settings.setAnswerWaitingWorkingTimeEnabled(false);
		Fixture f = new Fixture();
		// календарно после пятницы 17:30 час давно прошёл, хотя ночь и выходные
		f.check(at(24, 3, 0));
		verify(f.webSocketService, times(1)).userNotification(eq("operator"), any());
	}


	@Test
	void unansweredNotificationIsNotRepeated() {
		Fixture f = new Fixture();
		f.check(at(26, 10, 0));
		f.check(at(26, 11, 0));
		verify(f.webSocketService, times(1)).userNotification(eq("operator"), any());
	}


	private static ZonedDateTime at(int day, int hour, int minute) {
		return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, MSK);
	}


	private Duration waited(ZonedDateTime since, ZonedDateTime now) {
		return Duration.ofSeconds(service.waitedSeconds(since, now));
	}


	private final class Fixture {
		final WebSocketService webSocketService = mock(WebSocketService.class);
		final UserNotificationService notificationService;

		Fixture() {
			User operator = User.builder().id(7L).username("operator")
					.authorities(new ArrayList<>(List.of(Role.OPERATOR)))
					.notifyChatUnansweredTooLong(true)
					.notifyChatUnansweredTooLongMinutes(60)
					.build();
			Client client = Client.builder().id(3L).firstname("Иван").messages(new ArrayList<>()).build();
			client.getMessages().add(Message.builder().id(1L).isSent(false).isComment(false).deleted(false)
					.answerRequired(AnswerRequired.ANSWER_REQUIRED).date(at(23, 17, 30)).build());
			UserRepository userRepository = mock(UserRepository.class);
			when(userRepository.findAll()).thenReturn(List.of(operator));
			when(userRepository.findById(7L)).thenReturn(Optional.of(operator));
			ClientRepository clientRepository = mock(ClientRepository.class);
			when(clientRepository.findAll()).thenReturn(List.of(client));
			UserNotificationHistoryRepository history = mock(UserNotificationHistoryRepository.class);
			when(history.save(any())).thenAnswer(inv -> inv.getArgument(0));
			AppSettingsService slaSettings = mock(AppSettingsService.class);
			when(slaSettings.getSlaZones()).thenReturn(ru.ravel.ItDesk.dto.SlaZonesDto.defaults());
			notificationService = new UserNotificationService(userRepository, clientRepository, webSocketService,
					mock(TaskRepository.class), mock(SlaPauseRepository.class), history, service, slaSettings);
		}

		void check(ZonedDateTime now) {
			notificationService.checkUnansweredChatNotifications(now);
		}
	}

}
