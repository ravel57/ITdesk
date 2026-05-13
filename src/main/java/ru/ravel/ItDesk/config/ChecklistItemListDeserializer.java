package ru.ravel.ItDesk.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.ravel.ItDesk.model.ChecklistItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


public class ChecklistItemListDeserializer extends JsonDeserializer<List<ChecklistItem>> {

	@Override
	public List<ChecklistItem> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
		ObjectMapper mapper = (ObjectMapper) parser.getCodec();
		if (parser.currentToken() == JsonToken.VALUE_STRING) {
			String value = parser.getValueAsString();
			if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
				return new ArrayList<>();
			}
			JsonNode node = mapper.readTree(value);
			return parseNode(node);
		}
		JsonNode node = mapper.readTree(parser);
		return parseNode(node);
	}


	private List<ChecklistItem> parseNode(JsonNode node) {
		List<ChecklistItem> result = new ArrayList<>();
		if (node == null || node.isNull() || !node.isArray()) {
			return result;
		}
		for (JsonNode itemNode : node) {
			if (itemNode == null || itemNode.isNull()) {
				continue;
			}
			String text = itemNode.path("text").asText("").trim();
			if (text.isBlank()) {
				continue;
			}
			String id = itemNode.path("id").asText("");
			result.add(ChecklistItem.builder()
					.id(id == null || id.isBlank() ? UUID.randomUUID().toString() : id)
					.text(text)
					.completed(itemNode.path("completed").asBoolean(false))
					.build());
		}
		return result;
	}

}