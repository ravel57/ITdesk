package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import ru.ravel.ItDesk.dto.AutomationRoutingTestRequest;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutomationRoutingRequestHttpTest {
    @Test
    void jackson3EventPayloadReachesLegacyWorkflowWithoutModifyingTheRequest() throws Exception {
        String json = """
                {"eventPayload":{"client":{"id":7},"problem":{"severity":4},"items":[1,2]},
                 "workflowDefinition":"{}","parameters":{"threshold":0.8},
                 "analysisResults":{"decision":{"available":true,"probability":0.9}}}
                """;
        var input = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));
        input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var request = (AutomationRoutingTestRequest) new JacksonJsonHttpMessageConverter()
                .read(AutomationRoutingTestRequest.class, input);
        var engine = mock(AutomationWorkflowEngine.class);
        when(engine.test(anyString(), any())).thenReturn(Map.of("valid", true));
        var service = new AutomationRoutingTestService(mock(TaskRepository.class), mock(ClientRepository.class),
                mock(AutomationScriptRuntime.class), engine, mock(AutomationPayloadService.class), new ObjectMapper());

        assertThat(service.test(request)).containsEntry("valid", true);
        verify(engine).test(eq("{}"), argThat(payload -> payload.path("client").path("id").asInt() == 7
                && payload.path("items").size() == 2
                && payload.path("_automation").path("parameterOverrides").path("threshold").asDouble() == 0.8
                && payload.path("_automation").path("testResults").path("decision").path("available").asBoolean()));
        assertThat(request.getEventPayload()).doesNotContainKey("_automation");
    }
}
