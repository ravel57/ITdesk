package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.ravel.ItDesk.dto.UserDto;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.MessageFrom;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.SupportLineRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserInvitationTest {
    @Mock UserRepository userRepository;
    @Mock org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock OrganizationService organizationService;
    @Mock PasswordResetService passwordResetService;
    @Mock GlobalSearchService globalSearchService;
    @Mock EventPublisher eventPublisher;
    @Mock SupportLineRepository supportLineRepository;
    @Mock ClientRepository clientRepository;
    @InjectMocks UserService service;

    @Test
    void creatingUserWithoutPasswordSendsInvitationAfterSavingAccount() {
        UserDto request = new UserDto();
        request.setUsername("new@example.ru");
        request.setFirstname("Иван");
        request.setLastname("Иванов");
        request.setAuthorities(Role.OBSERVER.getName());
        when(passwordEncoder.encode(anyString())).thenReturn("generated-password-hash");
        when(userRepository.save(any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(7L);
            return user;
        });

        User created = service.newUser(request);

        assertTrue(created.getInvitePending());
        var order = inOrder(userRepository, passwordResetService);
        order.verify(userRepository).save(created);
        order.verify(passwordResetService).requestInviteLink("new@example.ru");
        verify(passwordResetService, never()).requestLink(anyString());
    }

    @Test
    void newPortalClientCardHasAnExplicitPortalChannel() {
        UserDto request = new UserDto();
        request.setUsername("portal@example.ru");
        request.setAuthorities(Role.CLIENT.getName());
        request.setCreateNewClientCard(true);
        request.setClientOrganizationId(5L);
        when(organizationService.getOrganizations()).thenReturn(List.of(Organization.builder().id(5L).build()));
        when(clientRepository.findById(7L)).thenReturn(Optional.of(Client.builder().id(7L)
                .organization(Organization.builder().id(5L).build()).build()));
        when(clientRepository.saveAndFlush(any())).thenAnswer(call -> {
            Client client = call.getArgument(0);
            client.setId(7L);
            return client;
        });
        when(userRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("generated-password-hash");

        assertEquals(7L, service.newUser(request).getClientId());
        verify(clientRepository).saveAndFlush(argThat(client -> client.getMessageFrom() == MessageFrom.PORTAL));
    }

    @Test
    void editingPortalUserDoesNotResetNotificationPreference() {
        User saved = User.builder().id(9L).username("portal@example.ru").authorities(List.of(Role.CLIENT))
                .clientId(7L).notifyPortalMessages(true).build();
        UserDto request = new UserDto();
        request.setId(9L);
        request.setAuthorities(Role.CLIENT.getName());
        request.setClientId(7L);
        request.setClientOrganizationId(5L);
        when(userRepository.findById(9L)).thenReturn(Optional.of(saved));
        when(userRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(organizationService.getOrganizations()).thenReturn(List.of(Organization.builder().id(5L).build()));
        when(clientRepository.findById(7L)).thenReturn(Optional.of(Client.builder().id(7L)
                .organization(Organization.builder().id(5L).build()).build()));
        assertTrue(service.updateUser(request).getNotifyPortalMessages());
    }

    @Test
    void resendingInvitationDoesNotRequestPasswordReset() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(User.builder().id(7L)
                .username("new@example.ru").invitePending(true).build()));

        service.resendInvite(7L);

        verify(passwordResetService).requestInviteLink("new@example.ru");
        verify(passwordResetService, never()).requestLink(anyString());
    }

    @Test
    void newUserJoinsSelectedLineWithoutReplacingExistingMembers() {
        UserDto request = requestWithLine(Role.OPERATOR);
        User existingMember = User.builder().id(1L).build();
        SupportLine line = SupportLine.builder().id(3L).members(List.of(existingMember)).build();
        when(supportLineRepository.findById(3L)).thenReturn(Optional.of(line));
        when(passwordEncoder.encode(anyString())).thenReturn("generated-password-hash");
        when(userRepository.save(any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(7L);
            return user;
        });

        User created = service.newUser(request);

        assertEquals(List.of(existingMember, created), line.getMembers());
        verify(supportLineRepository).save(line);
        verify(passwordResetService).requestInviteLink("new@example.ru");
    }

    @Test
    void inactiveLineIsRejectedBeforeCreatingUserOrSendingInvite() {
        when(supportLineRepository.findById(3L)).thenReturn(Optional.of(
                SupportLine.builder().id(3L).active(false).build()));
        assertThrows(IllegalArgumentException.class, () -> service.newUser(requestWithLine(Role.OPERATOR)));
        verify(userRepository, never()).save(any());
        verifyNoInteractions(passwordResetService);
    }

    @Test
    void missingLineIsRejectedBeforeCreatingUser() {
        assertThrows(IllegalArgumentException.class, () -> service.newUser(requestWithLine(Role.OPERATOR)));
        verify(userRepository, never()).save(any());
    }

    @Test
    void organizationObserverCannotBeAddedAsTeamMember() {
        assertThrows(IllegalArgumentException.class, () -> service.newUser(requestWithLine(Role.OBSERVER)));
        verifyNoInteractions(supportLineRepository, passwordResetService);
        verify(userRepository, never()).save(any());
    }

    private UserDto requestWithLine(Role role) {
        UserDto request = new UserDto();
        request.setUsername("new@example.ru");
        request.setAuthorities(role.getName());
        request.setSupportLineId(3L);
        return request;
    }
}
