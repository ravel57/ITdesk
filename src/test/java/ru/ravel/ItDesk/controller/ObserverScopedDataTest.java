package ru.ravel.ItDesk.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;
import ru.ravel.ItDesk.service.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Менеджер организации (OBSERVER) видит только данные своих организаций: SLA, каталог услуг;
 * глобальная сводка мониторинга, справочник критичностей и исполнители линии ему недоступны.
 */
class ObserverScopedDataTest {

	private final Organization own = Organization.builder().id(5L).name("Своя").build();
	private final Organization foreign = Organization.builder().id(6L).name("Чужая").build();
	private final Organization legacy = Organization.builder().id(7L).name("Без договоров").build();

	private final ru.ravel.ItDesk.model.Service ownService = ru.ravel.ItDesk.model.Service.builder().id(1L).name("Почта").orderNumber(1).build();
	private final ru.ravel.ItDesk.model.Service foreignService = ru.ravel.ItDesk.model.Service.builder().id(2L).name("1С").orderNumber(2).build();

	private UserService userService;
	private OrganizationServiceAssignmentRepository assignmentRepository;
	private ServiceCatalogService catalogService;

	@BeforeEach
	void setUp() {
		userService = mock(UserService.class, CALLS_REAL_METHODS);

		ServiceRepository serviceRepository = mock(ServiceRepository.class);
		when(serviceRepository.findAll()).thenReturn(List.of(ownService, foreignService));
		when(serviceRepository.findById(1L)).thenReturn(Optional.of(ownService));
		when(serviceRepository.findById(2L)).thenReturn(Optional.of(foreignService));

		OrganizationServiceAssignment ownAssignment = assignment(10L, own, ownService);
		OrganizationServiceAssignment foreignOnOwnService = assignment(11L, foreign, ownService);
		OrganizationServiceAssignment foreignAssignment = assignment(12L, foreign, foreignService);
		assignmentRepository = mock(OrganizationServiceAssignmentRepository.class);
		when(assignmentRepository.findAllByOrganizationIdOrderByDefaultForOrganizationDescIdAsc(5L)).thenReturn(List.of(ownAssignment));
		when(assignmentRepository.findAllByOrganizationIdOrderByDefaultForOrganizationDescIdAsc(6L)).thenReturn(List.of(foreignOnOwnService, foreignAssignment));
		when(assignmentRepository.findAllByOrganizationIdOrderByDefaultForOrganizationDescIdAsc(7L)).thenReturn(List.of());
		when(assignmentRepository.findAllByServiceIdOrderByOrganizationNameAsc(1L)).thenReturn(List.of(ownAssignment, foreignOnOwnService));
		when(assignmentRepository.findAllByServiceIdOrderByOrganizationNameAsc(2L)).thenReturn(List.of(foreignAssignment));

		catalogService = new ServiceCatalogService(serviceRepository, mock(ServiceCriticalityRepository.class),
				assignmentRepository, mock(OrganizationRepository.class), mock(UserRepository.class),
				mock(SupportLineRepository.class), mock(PriorityRepository.class), mock(TaskTypeRepository.class),
				mock(ZabbixIntegrationRepository.class), mock(TaskRepository.class), mock(IncidentRepository.class),
				mock(GlobalSearchService.class));
	}

	@Test
	void observerCatalogContainsOnlyServicesOfItsOrganizationsAndHidesForeignAssignments() {
		loginAs(user(Role.OBSERVER, own));

		List<?> services = (List<?>) new ServiceCatalogController(userService, catalogService).getServices(false).getBody();

		assertEquals(1, services.size());
		Map<?, ?> service = (Map<?, ?>) services.getFirst();
		assertEquals(1L, service.get("id"));
		List<?> assignments = (List<?>) service.get("assignments");
		assertEquals(1, assignments.size());
		assertEquals(5L, ((Map<?, ?>) ((Map<?, ?>) assignments.getFirst()).get("organization")).get("id"));
	}

	@Test
	void observerCannotOpenForeignService() {
		loginAs(user(Role.OBSERVER, own));
		ServiceCatalogController controller = new ServiceCatalogController(userService, catalogService);

		assertEquals(HttpStatus.FORBIDDEN, controller.getService(2L).getStatusCode());
		assertEquals(HttpStatus.OK, controller.getService(1L).getStatusCode());
		assertThrows(AccessDeniedException.class, () -> catalogService.getServiceForOrganizations(2L, Set.of(5L)));
	}

	@Test
	void observerOfLegacyOrganizationSeesWholeCatalogButNoForeignAssignments() {
		List<Map<String, Object>> services = catalogService.getServicesForOrganizations(Set.of(7L), false);

		assertEquals(List.of(1L, 2L), services.stream().map(dto -> dto.get("id")).toList());
		services.forEach(dto -> assertEquals(List.of(), dto.get("assignments")));
	}

	@Test
	void observerWithoutOrganizationsSeesNoServices() {
		assertEquals(List.of(), catalogService.getServicesForOrganizations(Set.of(), true));
	}

	@Test
	void operatorStillSeesFullCatalog() {
		loginAs(user(Role.OPERATOR, own));

		List<?> services = (List<?>) new ServiceCatalogController(userService, catalogService).getServices(false).getBody();

		assertEquals(2, services.size());
	}

	@Test
	void slaMatrixIsScopedForObserverOnly() {
		DefaultOrganization defaultOrganization = new DefaultOrganization();
		defaultOrganization.setId(1L);
		Map<Organization, Map<Priority, SlaValue>> sla = new HashMap<>();
		sla.put(own, Map.of());
		sla.put(foreign, Map.of());
		sla.put(defaultOrganization, Map.of());
		OrganizationService organizationService = mock(OrganizationService.class);
		when(organizationService.getSlaByPriority()).thenReturn(sla);
		SlaController controller = new SlaController(organizationService, mock(SlaService.class), mock(TaskService.class),
				mock(AppSettingsRepository.class), mock(ClientRepository.class), userService);

		loginAs(user(Role.OBSERVER, own));
		assertEquals(Set.of("Своя", "Стандартный SLA"), controller.getSlaByPriority().getBody().keySet());

		loginAs(user(Role.OPERATOR, own));
		assertEquals(Set.of("Своя", "Чужая", "Стандартный SLA"), controller.getSlaByPriority().getBody().keySet());
	}

	@Test
	void unscopedEndpointsAreDeniedToObserver() throws NoSuchMethodException {
		assertNoObserver(MonitoringController.class.getMethod("getServiceMonitoringSummary"));
		assertNoObserver(ServiceCatalogController.class.getMethod("getServiceCriticalities"));
		assertNoObserver(SupportLineController.class.getMethod("getSupportLineAssignableUsers", Long.class));
	}


	private void assertNoObserver(java.lang.reflect.Method method) {
		PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
		assertNotNull(preAuthorize, method.getName());
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("@access\\.can\\('(\\w+)'\\)").matcher(preAuthorize.value());
		assertTrue(matcher.matches(), method.getName() + ": " + preAuthorize.value());
		ru.ravel.ItDesk.model.Permission permission = ru.ravel.ItDesk.model.Permission.valueOf(matcher.group(1));
		assertFalse(ru.ravel.ItDesk.model.RolePermissions.of(Role.OBSERVER).contains(permission), method.getName());
		assertTrue(ru.ravel.ItDesk.model.RolePermissions.of(Role.OPERATOR).contains(permission), method.getName());
	}

	private OrganizationServiceAssignment assignment(Long id, Organization organization, ru.ravel.ItDesk.model.Service service) {
		return OrganizationServiceAssignment.builder().id(id).organization(organization).service(service).build();
	}

	private void loginAs(User user) {
		doReturn(user).when(userService).getCurrentUser();
	}

	private User user(Role role, Organization... organizations) {
		User user = new User();
		user.setId(99L);
		user.setUsername(role.name().toLowerCase());
		user.setAuthorities(List.of(role));
		user.setAvailableOrganizations(List.of(organizations));
		return user;
	}
}
