package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.ravel.ItDesk.component.AfterCommitExecutor;
import ru.ravel.ItDesk.component.PendingFileUploadRegistry;
import ru.ravel.ItDesk.controller.ClientPortalController;
import ru.ravel.ItDesk.dto.ClientPortalMessageDto;
import ru.ravel.ItDesk.dto.ClientPortalNotificationSettingsDto;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PortalNotificationSettingsTest {
    private final UserService users = mock(UserService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ClientRepository clients = mock(ClientRepository.class);
    private final ClientPortalSocketService sockets = mock(ClientPortalSocketService.class);
    private final AfterCommitExecutor afterCommit = mock(AfterCommitExecutor.class);
    private final User user = User.builder().id(7L).username("client@example.ru").clientId(11L).authorities(List.of(Role.CLIENT)).build();
    private final ClientPortalService service = new ClientPortalService(users, clients, mock(MessageRepository.class),
            mock(GlobalSearchService.class), mock(EventPublisher.class), mock(WebSocketService.class),
            mock(PendingFileUploadRegistry.class), userRepository, sockets, afterCommit);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void savesOnlyTheCurrentUsersPreferenceAndSynchronizesItAfterCommit(boolean enabled) {
        when(users.getCurrentUser()).thenReturn(user);
        when(users.isClient(user)).thenReturn(true);
        when(clients.findById(11L)).thenReturn(Optional.of(Client.builder().id(11L).build()));
        assertThat(service.getProfile().notificationsEnabled()).isFalse();
        var result = service.updateNotifications(new ClientPortalNotificationSettingsDto(enabled));
        assertThat(result.notificationsEnabled()).isEqualTo(enabled);
        verify(userRepository).save(argThat(saved -> saved.getId().equals(7L) && saved.getNotifyPortalMessages() == enabled));
        var action = ArgumentCaptor.forClass(Runnable.class);
        verify(afterCommit).run(action.capture());
        verifyNoInteractions(sockets);
        action.getValue().run();
        verify(sockets).sendNotificationSettings("client@example.ru", enabled);
        assertThat(service.getProfile().notificationsEnabled()).isEqualTo(enabled);
    }

    @Test
    void nonPortalUsersCannotChangeThePortalPreference() {
        when(users.getCurrentUser()).thenReturn(user);
        when(users.isClient(user)).thenReturn(false);
        assertThatThrownBy(() -> service.updateNotifications(new ClientPortalNotificationSettingsDto(true)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(userRepository, sockets, afterCommit);
    }

    @Test
    void disabledAlertsDoNotDisableChatDelivery() {
        var messaging = mock(SimpMessagingTemplate.class);
        when(userRepository.findAllByClientId(11L)).thenReturn(List.of(user));
        var portalSocket = new ClientPortalSocketService(messaging, userRepository);
        assertThat(user.getNotifyPortalMessages()).isFalse();
        portalSocket.sendMessage(Client.builder().id(11L).build(), Message.builder().id(20L).isSent(true).text("Ответ").build());
        verify(messaging).convertAndSendToUser(eq("client@example.ru"), eq("/queue/client-portal-messages"), any(ClientPortalMessageDto.class));
        portalSocket.sendNotificationSettings(user.getUsername(), false);
        verify(messaging).convertAndSendToUser("client@example.ru", "/queue/client-portal-settings", new ClientPortalNotificationSettingsDto(false));
    }

    @Test
    void endpointRequiresAnExplicitBooleanAndReturnsTheSavedPreference() throws Exception {
        var stub = mock(ClientPortalService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ClientPortalController(stub)).build();
        mvc.perform(put("/api/v1/portal/notifications").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(stub);
        when(stub.updateNotifications(new ClientPortalNotificationSettingsDto(false))).thenReturn(new ClientPortalNotificationSettingsDto(false));
        mvc.perform(put("/api/v1/portal/notifications").contentType("application/json").content("{\"notificationsEnabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notificationsEnabled").value(false));
    }
}
