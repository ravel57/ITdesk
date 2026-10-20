package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.ravel.ItDesk.dto.AutomationExpressionSuggestionRequest;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.MessageFrom;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AutomationMessageSourceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ClientRepository clients = mock(ClientRepository.class);
    private final AutomationPayloadService payloads = new AutomationPayloadService(clients, mock(TaskRepository.class), mapper);
    private final AutomationWorkflowValueService values = new AutomationWorkflowValueService(mapper);
    private final AutomationScriptRuntime scripts = new AutomationScriptRuntime(mapper,
            mock(AutomationActionExecutor.class), mock(AppSettingsService.class), values);

    @ParameterizedTest
    @ValueSource(strings = {"TELEGRAM", "WHATSAPP", "EMAIL", "PORTAL", "INTERNAL"})
    void latestMessageSourceCanBeUsedInConditionsAndTemplates(String source) {
        var date = ZonedDateTime.parse("2026-10-20T12:00:00Z");
        var latest = Message.builder().id(20L).fromSource(source).date(date).build();
        // The current client's delivery channel must not overwrite the individual message's source.
        var client = Client.builder().id(7L).messageFrom(MessageFrom.EMAIL).messages(List.of(
                latest, Message.builder().id(10L).fromSource("EMAIL").date(date.minusMinutes(1)).build())).build();
        when(clients.findById(7L)).thenReturn(Optional.of(client));
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("client").put("id", 7);
        payload.set("message", mapper.valueToTree(latest));
        payloads.enrich(Event.builder().payload(payload).build());

        assertThat(payload.path("client").path("lastMessage").path("id").asLong()).isEqualTo(20);
        assertThat(scripts.evaluateExpression("client.lastMessage.fromSource = '" + source + "'", payload)).isTrue();
        assertThat(scripts.evaluateExpression("message.fromSource = '" + source + "'", payload)).isTrue();
        assertThat(values.renderTemplate(payload, "Источник: {{ client.lastMessage.fromSource }}")).isEqualTo("Источник: " + source);
        assertThat(payload.path("client").path("messages").get(1).path("fromSource").asText()).isEqualTo(source);
    }

    @Test
    void refreshingLastMessageDoesNotChangeTheTriggeringMessagesSource() {
        var date = ZonedDateTime.parse("2026-10-20T12:00:00Z");
        when(clients.findById(7L)).thenReturn(Optional.of(Client.builder().id(7L).messages(List.of(
                Message.builder().id(20L).fromSource("TELEGRAM").date(date).build(),
                Message.builder().id(21L).fromSource("PORTAL").date(date).build())).build()));
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("client").put("id", 7);
        payload.putObject("message").put("id", 20).put("fromSource", "TELEGRAM");
        payloads.enrich(Event.builder().payload(payload).build());

        assertThat(payload.path("client").path("lastMessage").path("fromSource").asText()).isEqualTo("PORTAL");
        assertThat(payload.path("message").path("fromSource").asText()).isEqualTo("TELEGRAM");
    }

    @Test
    void missingMessageAndUnknownHistoricalSourceRemainNull() {
        var client = Client.builder().id(7L).messageFrom(MessageFrom.EMAIL).build();
        when(clients.findById(7L)).thenReturn(Optional.of(client));
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("client").put("id", 7);
        var event = Event.builder().payload(payload).build();
        payloads.enrich(event);
        assertThat(payload.path("client").path("lastMessage").isNull()).isTrue();
        assertThat(scripts.evaluateExpression("client.lastMessage.fromSource.isNull()", payload)).isTrue();

        client.setMessages(List.of(Message.builder().id(10L).build()));
        payloads.enrich(event);
        assertThat(payload.path("client").path("lastMessage").path("fromSource").isNull()).isTrue();
    }

    @Test
    void editorSuggestsTheSourceOnBothMessagePaths() {
        var metadata = new AutomationExpressionMetadataService(mapper);
        for (String path : List.of("client.lastMessage.from", "message.from")) {
            var response = metadata.suggest(AutomationExpressionSuggestionRequest.builder()
                    .text(path).cursor(path.length()).build());
            assertThat(response.toString()).contains("fromSource", "Источник сообщения", "PORTAL");
        }
    }
}
