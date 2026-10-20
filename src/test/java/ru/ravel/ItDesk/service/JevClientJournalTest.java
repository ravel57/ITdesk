package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.*;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JevClientJournalTest {
    private final JevSettingsService settings = mock(JevSettingsService.class);
    private final OutboundUrlGuard guard = mock(OutboundUrlGuard.class);
    private final JevDecisionRecorder recorder = mock(JevDecisionRecorder.class);
    private final HttpClient http = mock(HttpClient.class);
    private JevClient client;
    private final Map<String, Object> questions = Map.of("decision", Map.of("type", "noul"));

    @BeforeEach
    void setup() {
        client = new JevClient(settings, guard, recorder);
        ReflectionTestUtils.setField(client, "httpClient", http);
        when(settings.effective()).thenReturn(config(true));
        when(guard.validate(anyString())).thenReturn(URI.create("https://example.test/jev"));
    }
    private JevSettingsService.JevConfig config(boolean enabled) {
        return new JevSettingsService.JevConfig(enabled, "https://example.test/jev", "secret", "jev", Map.of(), 0.7, 0.3, java.util.List.of(), 1000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void successUsesTheSameJournalExchangeForEveryQuestion() throws Exception {
        var exchange = new JevDecisionRecorder.Exchange("context", java.util.List.of());
        when(recorder.begin("context", questions)).thenReturn(exchange);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn("{\"answers\":{\"decision\":{\"noul\":0.8}}}");
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertThat(client.evaluate("context", "state", questions)).isPresent();
        verify(recorder).begin("context", questions); verify(recorder).complete(eq(exchange), argThat(node -> node.path("decision").path("noul").asDouble() == 0.8));
    }

    @Test
    void disabledIntegrationIsRecordedAndDoesNotSendHttp() {
        when(settings.effective()).thenReturn(config(false));
        assertThat(client.evaluate("context", "state", questions)).isEmpty();
        verify(recorder).begin("context", questions); verify(recorder).unavailable(isNull(), eq("DISABLED"), anyString());
        verifyNoInteractions(http);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failureIsRecordedWithoutCopyingResponseBodyIntoTheJournal() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500); when(response.body()).thenReturn("secret response body");
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertThat(client.evaluate("context", "state", questions)).isEmpty();
        verify(recorder).unavailable(isNull(), eq("ERROR"), eq("Jev вернул HTTP 500"));
    }
}
