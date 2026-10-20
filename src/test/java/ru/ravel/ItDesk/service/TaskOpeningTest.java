package ru.ravel.ItDesk.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import ru.ravel.ItDesk.component.AfterCommitExecutor;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskOpeningTest {
    @Mock TaskRepository taskRepository;
    @Mock ClientRepository clientsRepository;
    @Mock UserService userService;
    @Mock OlaService olaService;
    @Mock AfterCommitExecutor afterCommitExecutor;
    @Mock WebSocketService webSocketService;
    @Mock EntityManager entityManager;
    @Mock AppSettingsRepository appSettingsRepository;
    @InjectMocks TaskService service;

    private User currentUser;

    @BeforeEach
    void setUp() {
        currentUser = User.builder().id(7L).username("executor").authorities(List.of(Role.ADMIN)).build();
    }

    @Test
    void countUsesCurrentExecutorFirstOpeningAndExistingActiveTaskRules() {
        when(userService.getCurrentUser()).thenReturn(currentUser);
        Query query = mock(Query.class);
        when(entityManager.createQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(2L);

        assertThat(service.countUnopenedAssignedTasks()).isEqualTo(2);

        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpql.capture());
        assertThat(jpql.getValue()).contains("executor = :openingCurrentUser",
                "not exists (select 1 from TaskOpening opening",
                "opening.taskId = t.id and opening.userId = :openingUserId",
                "coalesce(t.completed, false) = false", "coalesce(t.frozen, false) = false");
        verify(query).setParameter("openingCurrentUser", currentUser);
        verify(query).setParameter("openingUserId", 7L);
    }

    @Test
    void countKeepsOrganizationRestrictions() {
        currentUser.setAuthorities(List.of(Role.OBSERVER));
        currentUser.setAvailableOrganizations(List.of(Organization.builder().id(42L).build()));
        when(userService.getCurrentUser()).thenReturn(currentUser);
        Query query = mock(Query.class);
        when(entityManager.createQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(0L);

        service.countUnopenedAssignedTasks();

        verify(query).setParameter("availableOrganizationIds", List.of(42L));
    }

    @Test
    void openingIsPersonalAndNotifiesOnlyAfterCommit() {
        prepareTaskAccess();
        when(userService.getCurrentUser()).thenReturn(currentUser);
        when(taskRepository.markOpened(10L, 7L)).thenReturn(1);

        service.markTaskOpened(10L);

        verify(userService).assertCurrentUserCanAccessTask(any(Client.class), any(Task.class));
        verify(taskRepository).markOpened(10L, 7L);
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        verify(afterCommitExecutor).run(action.capture());
        verifyNoInteractions(webSocketService);
        action.getValue().run();
        verify(webSocketService).taskOpened(currentUser, 10L);
    }

    @Test
    void repeatedOpeningDoesNotPublishAnotherEvent() {
        prepareTaskAccess();
        when(userService.getCurrentUser()).thenReturn(currentUser);
        service.markTaskOpened(10L);
        verifyNoInteractions(afterCommitExecutor);
    }

    @Test
    void inaccessibleTaskCannotBeMarkedOpened() {
        prepareTaskAccess();
        doThrow(new AccessDeniedException("denied"))
                .when(userService).assertCurrentUserCanAccessTask(any(), any());
        assertThrows(AccessDeniedException.class, () -> service.markTaskOpened(10L));
        verify(taskRepository, never()).markOpened(anyLong(), anyLong());
        verifyNoInteractions(afterCommitExecutor);
    }

    private void prepareTaskAccess() {
        Task task = Task.builder().id(10L).build();
        when(clientsRepository.findByTaskId(10L)).thenReturn(Optional.of(Client.builder().id(20L).build()));
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
    }
}
