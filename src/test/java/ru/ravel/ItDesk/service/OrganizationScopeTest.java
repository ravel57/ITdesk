package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.ClientRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


/** Единое правило видимости организаций: {@link UserService#organizationScope(User)}. */
class OrganizationScopeTest {

	private final Organization own = Organization.builder().id(1L).name("Своя").build();
	private final Organization foreign = Organization.builder().id(2L).name("Чужая").build();
	private final Client ownClient = Client.builder().id(10L).organization(own).build();
	private final Client foreignClient = Client.builder().id(20L).organization(foreign).build();
	private final Client noOrganizationClient = Client.builder().id(30L).build();
	private UserService userService;


	@BeforeEach
	void setUp() {
		userService = mock(UserService.class, CALLS_REAL_METHODS);
		ClientRepository clientRepository = mock(ClientRepository.class);
		when(clientRepository.findById(10L)).thenReturn(Optional.of(ownClient));
		ReflectionTestUtils.setField(userService, "clientRepository", clientRepository);
	}


	@Test
	void adminAndManagerSeeEverything() {
		for (Role role : List.of(Role.ADMIN, Role.MANAGER)) {
			OrganizationScope scope = userService.organizationScope(user(role, own));
			assertTrue(scope.all(), role.name());
			assertEquals(3, userService.filterClientsByUser(List.of(ownClient, foreignClient, noOrganizationClient), user(role, own)).size());
		}
	}


	@Test
	void operatorsAreLimitedToAssignedOrganizationsOrSeeAllWithoutAssignments() {
		for (Role role : List.of(Role.OPERATOR, Role.SENIOR_OPERATOR)) {
			assertTrue(userService.organizationScope(user(role)).all(), role.name());
			OrganizationScope restricted = userService.organizationScope(user(role, own));
			assertEquals(Set.of(1L), restricted.organizationIds(), role.name());
			assertTrue(restricted.allows(1L));
			assertFalse(restricted.allows(2L));
			assertFalse(restricted.allows(null), "клиент без организации не виден оператору с ограничением");
		}
	}


	@Test
	void observerWithoutOrganizationsSeesNothing() {
		assertEquals(OrganizationScope.NONE, userService.organizationScope(user(Role.OBSERVER)));
		assertEquals(List.of(ownClient),
				userService.filterClientsByUser(List.of(ownClient, foreignClient, noOrganizationClient), user(Role.OBSERVER, own)));
	}


	@Test
	void clientSeesOnlyOrganizationOfItsCard() {
		User client = user(Role.CLIENT);
		client.setClientId(10L);
		assertEquals(Set.of(1L), userService.organizationScope(client).organizationIds());
		assertEquals(List.of(ownClient), userService.filterClientsByUser(List.of(ownClient, foreignClient), client));
	}


	@Test
	void unknownOrMissingUserIsClosedByDefault() {
		assertEquals(OrganizationScope.NONE, userService.organizationScope(null));
		User noRoles = new User();
		noRoles.setAuthorities(List.of());
		assertEquals(OrganizationScope.NONE, userService.organizationScope(noRoles));
		assertTrue(userService.filterClientsByUser(List.of(ownClient), noRoles).isEmpty());
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
