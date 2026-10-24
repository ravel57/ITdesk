package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.Task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;


/** Сервис организации по умолчанию не подставляется в заявку, намеренно созданную без сервиса. */
class TaskDefaultServiceTest {

	private final ServiceCatalogService catalog = mock(ServiceCatalogService.class);
	private final TaskService taskService = mock(TaskService.class, CALLS_REAL_METHODS);
	private final Organization organization = Organization.builder().id(2L).name("test").build();
	private final Client client = Client.builder().id(7L).organization(organization).build();
	private final ru.ravel.ItDesk.model.Service oneC = ru.ravel.ItDesk.model.Service.builder().id(4L).name("1C").build();
	private final ru.ravel.ItDesk.model.TaskType type = ru.ravel.ItDesk.model.TaskType.builder().id(1L).type("Запрос").build();


	@BeforeEach
	void setUp() throws Exception {
		// Остальные зависимости prepareTaskBeforeSave (типы, приоритеты, статусы) — пустые заглушки
		for (var field : TaskService.class.getDeclaredFields()) {
			if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
					|| field.getType().isArray() || field.getType().getName().startsWith("java.")) continue;
			field.setAccessible(true);
			if (field.get(taskService) == null) field.set(taskService, mock(field.getType()));
		}
		ReflectionTestUtils.setField(taskService, "serviceCatalogService", catalog);
		var types = (ru.ravel.ItDesk.repository.TaskTypeRepository) ReflectionTestUtils.getField(taskService, "taskTypeRepository");
		when(types.findById(1L)).thenReturn(java.util.Optional.of(type));
		when(catalog.resolveService(isNull(), any())).thenReturn(oneC);
		when(catalog.resolveService(any(ru.ravel.ItDesk.model.Service.class), any())).thenAnswer(call -> call.getArgument(0));
	}


	@Test
	void taskWithoutServiceGetsSingleOrganizationServiceByDefault() {
		Task task = Task.builder().name("Принтер").type(type).build();
		taskService.prepareTaskBeforeSave(task, client);
		assertThat(task.getService()).isEqualTo(oneC);
	}


	@Test
	void taskMarkedWithoutDefaultServiceStaysWithoutService() {
		Task task = Task.builder().name("Принтер").type(type).noDefaultService(true).build();
		taskService.prepareTaskBeforeSave(task, client);
		assertThat(task.getService()).isNull();
		verify(catalog, never()).resolveService(any(), any());
		verify(catalog).applyTaskDefaults(task, organization);
	}


	@Test
	void explicitServiceClearsTheMark() {
		Task task = Task.builder().name("1С").type(type).service(oneC).noDefaultService(true).build();
		taskService.prepareTaskBeforeSave(task, client);
		assertThat(task.getService()).isEqualTo(oneC);
		assertThat(task.getNoDefaultService()).isFalse();
	}

}
