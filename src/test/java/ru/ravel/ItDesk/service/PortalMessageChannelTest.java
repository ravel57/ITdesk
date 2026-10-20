package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.component.PendingFileUploadRegistry;
import ru.ravel.ItDesk.dto.ClientMessage;
import ru.ravel.ItDesk.dto.ClientPortalMessageRequest;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PortalMessageChannelTest {
    private final ClientRepository clients = mock(ClientRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final WebSocketService sockets = mock(WebSocketService.class);
    private final UserService users = mock(UserService.class);

    @Test
    void incomingPortalMessageKeepsItsSourceWhenClientAlsoUsesEmail() {
        Client client = Client.builder().id(7L).messageFrom(MessageFrom.EMAIL).build();
        Message received = receive(client);
        assertThat(received.getFromSource()).isEqualTo("PORTAL");
        assertThat(client.getMessageFrom()).isEqualTo(MessageFrom.EMAIL);
        verify(events).publish(eq(TriggerType.MESSAGE_INCOMING), argThat((Map<String, Object> p) ->
                ((Message) p.get("message")).getFromSource().equals("PORTAL")));
        verify(sockets).sendNewMessages(argThat(c -> c.getMessage() == received));
    }

    @Test
    void portalMessageIdentifiesLegacyCardWithoutAChannel() {
        Client client = Client.builder().id(7L).build();
        receive(client);
        assertThat(client.getMessageFrom()).isEqualTo(MessageFrom.PORTAL);
    }

    private Message receive(Client client) {
        var user = User.builder().id(9L).clientId(7L).build();
        when(users.getCurrentUser()).thenReturn(user);
        when(users.isClient(user)).thenReturn(true);
        when(clients.findById(7L)).thenReturn(Optional.of(client));
        when(messages.saveAndFlush(any())).thenAnswer(call -> {
            Message message = call.getArgument(0);
            message.setId(20L);
            return message;
        });
        var portal = new ClientPortalService(users, clients, messages, mock(GlobalSearchService.class), events,
                sockets, mock(PendingFileUploadRegistry.class));
        var request = new ClientPortalMessageRequest();
        request.setText("Сообщение из личного кабинета");
        portal.sendMessage(request);
        var saved = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(messages).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    @Test
    void replyToPortalIsSavedAndPublishedWithoutExternalDelivery() throws Exception {
        Client client = Client.builder().id(7L).messageFrom(MessageFrom.PORTAL).build();
        when(clients.findById(7L)).thenReturn(Optional.of(client));
        when(clients.saveAndFlush(any())).thenAnswer(c -> c.getArgument(0));
        when(messages.saveAndFlush(any())).thenAnswer(c -> {
            Message message = c.getArgument(0);
            message.setId(21L);
            return message;
        });
        var dependencies = Map.<Class<?>, Object>of(ClientRepository.class, clients, MessageRepository.class, messages,
                EventPublisher.class, events, WebSocketService.class, sockets, UserService.class, users);
        var constructor = ClientService.class.getConstructors()[0];
        var service = (ClientService) constructor.newInstance(Arrays.stream(constructor.getParameterTypes())
                .map(t -> dependencies.containsKey(t) ? dependencies.get(t) : mock(t)).toArray());
        Message reply = service.sendMessageWithUser(7L, Message.builder().text("Ответ поддержки").build(),
                User.builder().id(9L).build());
        assertThat(reply.getFromSource()).isEqualTo("PORTAL");
        assertThat(client.getMessages()).contains(reply);
        verify(events).publish(eq(TriggerType.MESSAGE_OUTGOING), any());
        verify(events, never()).publish(eq(TriggerType.COMMUNICATION_SEND_REQUESTED), any());
        verify(sockets).sendNewMessages(any(ClientMessage.class));
    }

    @Test
    void queuedPortalDeliveryDoesNotRequireAnExternalSender() throws Exception {
        when(clients.findById(7L)).thenReturn(Optional.of(Client.builder().id(7L).build()));
        when(messages.findById(21L)).thenReturn(Optional.of(Message.builder().id(21L).build()));
        when(messages.existsByIdAndClientId(21L, 7L)).thenReturn(true);
        var externalSender = mock(CommunicationChannelSender.class);
        when(externalSender.channel()).thenReturn(MessageFrom.EMAIL);
        var delivery = new CommunicationDeliveryService(clients, messages, List.of(externalSender));
        var payload = new ObjectMapper().createObjectNode().put("clientId", 7).put("messageId", 21).put("channel", "PORTAL");
        delivery.deliver(Event.builder().payload(payload).build());
        verify(externalSender, never()).send(any(), any());
    }
}
