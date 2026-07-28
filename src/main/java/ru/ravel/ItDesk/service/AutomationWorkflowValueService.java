package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
@RequiredArgsConstructor
public class AutomationWorkflowValueService {

	private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}", Pattern.MULTILINE);
	private static final Pattern PATH = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+");

	private final ObjectMapper objectMapper;


	public Object resolve(JsonNode payload, String expression) {
		if (expression == null) return null;
		String value = expression.trim();
		if (value.isEmpty()) return "";

		Matcher exactTemplate = TEMPLATE.matcher(value);
		if (exactTemplate.matches()) {
			return unwrap(readPath(payload, exactTemplate.group(1).trim()));
		}
		if (TEMPLATE.matcher(value).find()) {
			return renderTemplate(payload, value);
		}
		if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\""))) {
			return value.substring(1, value.length() - 1)
					.replace("\\'", "'")
					.replace("\\\"", "\"");
		}
		if ("null".equalsIgnoreCase(value)) return null;
		if ("true".equalsIgnoreCase(value)) return true;
		if ("false".equalsIgnoreCase(value)) return false;
		if (PATH.matcher(value).matches()) {
			JsonNode node = readPath(payload, value);
			if (node != null && !node.isMissingNode()) return unwrap(node);
		}
		try {
			return new BigDecimal(value);
		} catch (NumberFormatException ignored) {
		}
		if (looksNumericExpression(value)) {
			return new NumericParser(value, payload).parse();
		}
		return value;
	}


	public String renderTemplate(JsonNode payload, String template) {
		if (template == null) return null;
		Matcher matcher = TEMPLATE.matcher(template);
		StringBuffer result = new StringBuffer();
		while (matcher.find()) {
			Object resolved = unwrap(readPath(payload, matcher.group(1).trim()));
			matcher.appendReplacement(result, Matcher.quoteReplacement(resolved == null ? "" : String.valueOf(resolved)));
		}
		matcher.appendTail(result);
		return result.toString();
	}


	public JsonNode readPath(JsonNode payload, String path) {
		if (payload == null || payload.isNull() || path == null || path.isBlank()) return null;
		String normalized = path.trim();
		JsonNode current;
		if (normalized.startsWith("var.")) {
			current = payload.path("_automation").path("variables");
			normalized = normalized.substring(4);
		} else {
			current = payload;
		}
		for (String part : normalized.split("\\.")) {
			if (current == null || current.isNull() || current.isMissingNode()) return null;
			current = current.path(part);
		}
		return current == null || current.isMissingNode() ? null : current;
	}


	public void setVariable(JsonNode payload, String name, Object value) {
		if (!(payload instanceof ObjectNode root)) {
			throw new IllegalArgumentException("Payload автоматизации должен быть JSON-объектом");
		}
		String safeName = normalizeVariableName(name);
		ObjectNode automation = root.with("_automation");
		ObjectNode variables = automation.with("variables");
		variables.set(safeName, objectMapper.valueToTree(value));
	}


	public Object getVariable(JsonNode payload, String name) {
		JsonNode node = readPath(payload, "var." + normalizeVariableName(name));
		return unwrap(node);
	}


	public long getLongVariable(JsonNode payload, String name, long fallback) {
		Object value = getVariable(payload, name);
		if (value instanceof Number number) return number.longValue();
		if (value != null) {
			try {
				return Long.parseLong(String.valueOf(value));
			} catch (NumberFormatException ignored) {
			}
		}
		return fallback;
	}


	public Long resolveLong(JsonNode payload, String expression) {
		Object value = resolve(payload, expression);
		if (value instanceof Number number) return number.longValue();
		if (value == null) return null;
		try {
			return new BigDecimal(String.valueOf(value)).longValue();
		} catch (NumberFormatException ignored) {
			return null;
		}
	}


	public String resolveString(JsonNode payload, String expression) {
		Object value = resolve(payload, expression);
		return value == null ? null : String.valueOf(value);
	}


	public String correlationKey(JsonNode payload, String scope, String customExpression) {
		String normalizedScope = scope == null ? "TASK" : scope.trim().toUpperCase(Locale.ROOT);
		return switch (normalizedScope) {
			case "GLOBAL" -> "global";
			case "CLIENT" -> "client:" + safeKey(resolveLong(payload, "client.id"));
			case "CUSTOM" -> "custom:" + safeKey(resolveString(payload, customExpression));
			default -> "task:" + safeKey(resolveLong(payload, "task.id"));
		};
	}


	public JsonNode mergeRuntimeState(JsonNode previousPayload, JsonNode incomingPayload) {
		ObjectNode result;
		JsonNode resultNode = incomingPayload == null ? null : incomingPayload.deepCopy();
		if (resultNode instanceof ObjectNode objectNode) {
			result = objectNode;
		} else {
			result = objectMapper.createObjectNode();
			if (incomingPayload != null) result.set("event", incomingPayload.deepCopy());
		}
		JsonNode automation = previousPayload == null ? null : previousPayload.path("_automation");
		if (automation != null && !automation.isMissingNode() && !automation.isNull()) {
			result.set("_automation", automation.deepCopy());
		}
		return result;
	}


	public boolean valuesEqual(Object left, Object right) {
		if (left == null || right == null) return left == right;
		if (left instanceof Number || right instanceof Number) {
			try {
				return new BigDecimal(String.valueOf(left)).compareTo(new BigDecimal(String.valueOf(right))) == 0;
			} catch (NumberFormatException ignored) {
			}
		}
		return String.valueOf(left).equalsIgnoreCase(String.valueOf(right));
	}


	private String safeKey(Object value) {
		return value == null || String.valueOf(value).isBlank() ? "none" : String.valueOf(value).trim();
	}


	private String normalizeVariableName(String name) {
		if (name == null || name.isBlank()) throw new IllegalArgumentException("Имя переменной не заполнено");
		String normalized = name.trim();
		if (normalized.startsWith("var.")) normalized = normalized.substring(4);
		if (!normalized.matches("[a-zA-Z_][a-zA-Z0-9_-]{0,127}")) {
			throw new IllegalArgumentException("Некорректное имя переменной: " + name);
		}
		return normalized;
	}


	private Object unwrap(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) return null;
		if (node.isTextual()) return node.asText();
		if (node.isBoolean()) return node.asBoolean();
		if (node.isIntegralNumber()) return node.longValue();
		if (node.isFloatingPointNumber()) return node.decimalValue();
		return node;
	}


	private boolean looksNumericExpression(String expression) {
		return expression.matches(".*[+\\-*/()].*")
				&& expression.matches("[a-zA-Z0-9_.'\\s+\\-*/().]+")
				&& !expression.contains("{{");
	}


	private final class NumericParser {
		private final String source;
		private final JsonNode payload;
		private int index;

		private NumericParser(String source, JsonNode payload) {
			this.source = source;
			this.payload = payload;
		}

		BigDecimal parse() {
			BigDecimal result = expression();
			skipSpaces();
			if (index != source.length()) throw new IllegalArgumentException("Некорректная формула: " + source);
			return result.stripTrailingZeros();
		}

		private BigDecimal expression() {
			BigDecimal value = term();
			while (true) {
				skipSpaces();
				if (consume('+')) value = value.add(term());
				else if (consume('-')) value = value.subtract(term());
				else return value;
			}
		}

		private BigDecimal term() {
			BigDecimal value = factor();
			while (true) {
				skipSpaces();
				if (consume('*')) value = value.multiply(factor());
				else if (consume('/')) value = value.divide(factor(), MathContext.DECIMAL64);
				else return value;
			}
		}

		private BigDecimal factor() {
			skipSpaces();
			if (consume('+')) return factor();
			if (consume('-')) return factor().negate();
			if (consume('(')) {
				BigDecimal value = expression();
				if (!consume(')')) throw new IllegalArgumentException("Не закрыта скобка: " + source);
				return value;
			}
			int start = index;
			while (index < source.length()) {
				char c = source.charAt(index);
				if (Character.isLetterOrDigit(c) || c == '_' || c == '.') index++;
				else break;
			}
			if (start == index) throw new IllegalArgumentException("Ожидалось число или переменная: " + source);
			String token = source.substring(start, index);
			try {
				return new BigDecimal(token);
			} catch (NumberFormatException ignored) {
				Object value = resolve(payload, token);
				if (value == null) return BigDecimal.ZERO;
				try {
					return new BigDecimal(String.valueOf(value));
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Переменная не является числом: " + token);
				}
			}
		}

		private boolean consume(char expected) {
			skipSpaces();
			if (index < source.length() && source.charAt(index) == expected) {
				index++;
				return true;
			}
			return false;
		}

		private void skipSpaces() {
			while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
		}
	}
}