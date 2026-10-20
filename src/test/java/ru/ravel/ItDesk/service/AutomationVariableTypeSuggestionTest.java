package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;
import ru.ravel.ItDesk.dto.AutomationExpressionSuggestionRequest;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationVariableTypeSuggestionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AutomationExpressionMetadataService service = new AutomationExpressionMetadataService(mapper);
    private final Map<String, Object> task = object(Map.of("name", type("STRING"), "createdAt", type("DATE_TIME"), "id", type("NUMBER")));
    private final Map<String, Object> variables = Map.of(
            "matched", Map.of("type", "LIST", "element", object(Map.of("task", task, "probability", type("NUMBER")))),
            "settings", object(Map.of("threshold", type("NUMBER"), "enabled", type("BOOLEAN"))),
            "text", type("STRING"));

    @Test
    void jackson3HttpConverterReadsTypedVariablesAndWritesSuggestions() throws Exception {
        String json = """
                {"text":"var.allTasks.si","cursor":15,"variables":["allTasks"],
                 "variableTypes":{"allTasks":{"type":"LIST","element":{"type":"OBJECT",
                   "fields":{"id":{"type":"NUMBER"},"name":{"type":"STRING"}}}}}}
                """;
        var converter = new JacksonJsonHttpMessageConverter();
        var input = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));
        input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var request = (AutomationExpressionSuggestionRequest) converter.read(AutomationExpressionSuggestionRequest.class, input);
        var response = service.suggest(request);
        var output = new MockHttpOutputMessage();
        converter.write(response, MediaType.APPLICATION_JSON, output);
        assertThat(output.getBodyAsString()).contains("size()", "var.allTasks.size()");
    }

    @Test
    void jackson3HttpConverterStillAcceptsRequestsWithoutVariableTypes() throws Exception {
        var converter = new JacksonJsonHttpMessageConverter();
        var input = new MockHttpInputMessage("{\"text\":\"var.custom.\",\"variables\":[\"custom.name\"]}".getBytes(StandardCharsets.UTF_8));
        input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var request = (AutomationExpressionSuggestionRequest) converter.read(AutomationExpressionSuggestionRequest.class, input);
        assertThat(service.suggest(request).toString()).contains("name");
    }

    @Test
    void listMethodsAreSuggestedWithTheCorrectReplacementRange() {
        var request = request("var.matched.si", "EXPRESSION", variables);
        var response = service.suggest(request);
        assertThat((List<?>) response.get("suggestions")).anySatisfy(item -> {
            @SuppressWarnings("unchecked") var suggestion = (Map<String, Object>) item;
            assertThat(suggestion).containsEntry("insertText", "size()").containsEntry("type", "int");
        });
        assertThat(response).containsEntry("replaceFrom", 12).containsEntry("replaceTo", 14);
        assertThat(labels("var.matched.")).contains("size()", "first()", "last()", "isEmpty()", "notEmpty()");
        assertThat(labels("var.matched.")).noneMatch(label -> label.startsWith("trim"));
    }

    @Test
    void followsListElementsObjectsAndScalarMethods() {
        assertThat(labels("var.matched.first().")).contains("task", "probability");
        assertThat(labels("var.matched.last().task.")).contains("name", "createdAt", "id");
        assertThat(labels("var.matched.first().task.name.")).contains("trim()", "lower()");
        assertThat(labels("var.matched.first().task.createdAt.")).contains("minutesSince()", "daysSince()", "isToday()");
        assertThat(labels("var.text.trim().")).contains("upper()", "length()");
        assertThat(labels("var.settings.enabled.")).contains("isTrue()", "isFalse()");
        assertThat(labels("var.settings.threshold.")).doesNotContain("size()", "trim()");
    }

    @Test
    void typesWorkInActionArgumentsAndTemplateExpressionsAndAtTheRoot() {
        var action = service.suggest(request("client.sendMessage(var.matched.si", "ACTION", variables));
        assertThat(action.toString()).contains("size()");
        assertThat(labels("var.")).contains("matched", "settings", "text");
        assertThat(labels("var.settings.")).contains("threshold", "enabled");
        assertThat(service.suggest(request("var.matched.fi", "TEMPLATE", variables)).toString()).contains("first()");
    }

    @Test
    void retainsNameOnlyCompatibilityAndDoesNotInventACollectionType() {
        var request = request("var.custom.", "EXPRESSION", Map.of());
        request.setVariables(List.of("custom.name", "custom.value"));
        assertThat(service.suggest(request).toString()).contains("name", "value").doesNotContain("size()");
        assertThat(service.suggest(request("var.untyped.", "EXPRESSION", Map.of("untyped", type("java.lang.Runtime")))).toString())
                .doesNotContain("getRuntime", "exec", "size()");
    }

    @Test
    void typeEditsApplyImmediatelyAndSensitiveFieldsAreFiltered() {
        var request = request("var.value.", "EXPRESSION", Map.of("value", Map.of("type", "LIST", "element", task)));
        assertThat(service.suggest(request).toString()).contains("size()");
        request.setVariableTypes(Map.of("value", type("STRING")));
        assertThat(service.suggest(request).toString()).contains("trim()").doesNotContain("size()");
        assertThat(service.suggest(request("var.secret.", "EXPRESSION", Map.of("secret", object(Map.of("password", type("STRING"), "name", type("STRING")))))).toString())
                .contains("name").doesNotContain("password");
    }

    @SuppressWarnings("unchecked")
    private List<String> labels(String text) {
        return ((List<Map<String, Object>>) service.suggest(request(text, "EXPRESSION", variables)).get("suggestions"))
                .stream().map(item -> item.get("label").toString()).toList();
    }

    private AutomationExpressionSuggestionRequest request(String text, String mode, Map<String, Object> types) {
        var request = new AutomationExpressionSuggestionRequest();
        request.setText(text);
        request.setCursor(text.length());
        request.setMode(mode);
        request.setVariableTypes(new java.util.LinkedHashMap<>(types));
        return request;
    }

    private static Map<String, Object> type(String name) { return Map.of("type", name); }
    private static Map<String, Object> object(Map<String, Object> fields) { return Map.of("type", "OBJECT", "fields", fields); }
}
