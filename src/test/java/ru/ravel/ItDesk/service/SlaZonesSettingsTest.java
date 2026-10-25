package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.dto.SlaZonesDto;
import ru.ravel.ItDesk.model.AppSettings;
import ru.ravel.ItDesk.model.Sla;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AppSettingsRepository;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SlaZonesSettingsTest {

	@Test
	void invalidOrMissingZonesFallBackToDefaults() {
		assertThat(SlaZonesDto.of(null, null)).isEqualTo(SlaZonesDto.defaults());
		assertThat(SlaZonesDto.of(25, 50)).isEqualTo(SlaZonesDto.defaults());
		assertThat(SlaZonesDto.of(100, 25)).isEqualTo(SlaZonesDto.defaults());
		assertThat(SlaZonesDto.of(0, 0)).isEqualTo(SlaZonesDto.defaults());
		assertThat(SlaZonesDto.of(70, 30)).isEqualTo(new SlaZonesDto(70, 30));
	}


	@Test
	void savingRejectsRedZoneThatStartsAboveOrange() {
		AppSettingsRepository repository = mock(AppSettingsRepository.class);
		AppSettings stored = AppSettings.builder().id(1L).build();
		when(repository.findById(1L)).thenReturn(java.util.Optional.of(stored));
		AppSettingsService service = new AppSettingsService(repository);

		AppSettings payload = generalPayload();
		payload.setSlaOrangeStartPercent(30);
		payload.setSlaRedStartPercent(40);
		assertThatThrownBy(() -> service.updateGeneralSettings(payload)).isInstanceOf(IllegalArgumentException.class);
		verify(repository, never()).save(any());
	}


	@Test
	void savingStoresZonesAndKeepsThemWhenAnOldClientOmitsThem() {
		AppSettingsRepository repository = mock(AppSettingsRepository.class);
		AppSettings stored = AppSettings.builder().id(1L).build();
		when(repository.findById(1L)).thenReturn(java.util.Optional.of(stored));
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		AppSettingsService service = new AppSettingsService(repository);

		AppSettings payload = generalPayload();
		payload.setSlaOrangeStartPercent(70);
		payload.setSlaRedStartPercent(30);
		service.updateGeneralSettings(payload);
		assertThat(service.getSlaZones()).isEqualTo(new SlaZonesDto(70, 30));

		AppSettings withoutZones = generalPayload();
		withoutZones.setSlaOrangeStartPercent(null);
		withoutZones.setSlaRedStartPercent(null);
		service.updateGeneralSettings(withoutZones);
		assertThat(service.getSlaZones()).isEqualTo(new SlaZonesDto(70, 30));
	}


	@Test
	void slaWarningFiresWhereTheOrangeZoneStartsInsteadOfAtHalf() {
		TaskRepository taskRepository = mock(TaskRepository.class);
		SlaService slaService = mock(SlaService.class);
		WorkingTimeService workingTimeService = mock(WorkingTimeService.class);
		EventPublisher eventPublisher = mock(EventPublisher.class);
		AutomationOutboxRepository outbox = mock(AutomationOutboxRepository.class);
		AppSettingsService settings = mock(AppSettingsService.class);
		when(settings.getSlaZones()).thenReturn(new SlaZonesDto(70, 30));
		when(workingTimeService.getCurrentState()).thenReturn(new WorkingTimeService.WorkingTimeState(false, true));
		when(taskRepository.findAllActiveAwaitingFirstResponse()).thenReturn(List.of());

		Sla early = Sla.builder().id(1L).startDate(ZonedDateTime.now().minusMinutes(20)).duration(Duration.ofMinutes(100)).build();
		Sla late = Sla.builder().id(2L).startDate(ZonedDateTime.now().minusMinutes(40)).duration(Duration.ofMinutes(100)).build();
		Task notYet = Task.builder().id(10L).sla(early).build();
		Task inOrange = Task.builder().id(11L).sla(late).build();
		when(taskRepository.findAllActiveWithSla()).thenReturn(List.of(notYet, inOrange));
		when(slaService.isPaused(any())).thenReturn(false);
		// 80 минут из 100 осталось (вне зоны), 60 из 100 (оранжевая зона начинается с 70)
		when(slaService.remaining(early)).thenReturn(Duration.ofMinutes(80));
		when(slaService.remaining(late)).thenReturn(Duration.ofMinutes(60));

		new SlaEventWorker(taskRepository, slaService, workingTimeService, eventPublisher, outbox, settings).checkSla();

		verify(eventPublisher, never()).publish(eq(TriggerType.SLA_WARNING), argThatTaskId(10L));
		verify(eventPublisher, times(1)).publish(eq(TriggerType.SLA_WARNING), argThatTaskId(11L));
	}


	private static java.util.Map<String, Object> argThatTaskId(long id) {
		return argThat(payload -> payload != null && payload.get("task") instanceof java.util.Map<?, ?> task && Long.valueOf(id).equals(task.get("id")));
	}


	private static AppSettings generalPayload() {
		AppSettings payload = AppSettings.builder().build();
		payload.setTimezone("Europe/Moscow");
		payload.setWorkingTimeEnabled(false);
		payload.setWorkdayStart("09:00");
		payload.setWorkdayEnd("18:00");
		return payload;
	}
}
