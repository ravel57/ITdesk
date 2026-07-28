package ru.ravel.ItDesk.service;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.model.automatosation.TriggerFunctionsType;
import ru.ravel.ItDesk.model.automatosation.TriggerOperationType;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.ravel.ItDesk.model.AppSettings;


/**
 * Позволяет писать "скрипты" как на скрине:
 * Выражение:
 * client.messages.size() = 1 && starts_with(message.text, 'привет')
 * Действие:
 * client.sendMessage('Здравствуйте!')
 * ticket.setStatus('IN_PROGRESS');
 * ticket.addTag('vip');
 * Поддержка:
 * - операции из TriggerOperationType: =, >, >=, <, <=, in, &&, ||, !
 * - функции из TriggerFunctionsType: starts_with, ends_with, any_of, none_of, all_of, is_null, not_null, is_empty...
 * - размер коллекции: .size() (как в примере client.messages.size())
 * - действие: target.method(args)
 */
@Service
@RequiredArgsConstructor
public class AutomationScriptRuntime {

	private final ObjectMapper mapper;
	private final AutomationActionExecutor actionExecutor;
	private final AppSettingsService appSettingsService;
	private static final ThreadLocal<AutomationTimeSettings> CURRENT_TIME_SETTINGS = ThreadLocal.withInitial(AutomationTimeSettings::defaultSettings);

	// ------------------------- PUBLIC API -------------------------

	/**
	 * Вернёт true/false по выражению (как в UI поле "Выражение")
	 */
	public boolean evaluateExpression(String expression, JsonNode payloadRoot) {
		if (expression == null || expression.isBlank()) {
			return true;
		}
		CURRENT_TIME_SETTINGS.set(resolveAutomationTimeSettings());
		try {
			Lexer lx = new Lexer(expression);
			Parser parser = new Parser(lx);
			ExprNode ast = parser.parseExpression();
			return asBool(ast.eval(payloadRoot));
		} finally {
			CURRENT_TIME_SETTINGS.remove();
		}
	}


	private AutomationTimeSettings resolveAutomationTimeSettings() {
		try {
			AppSettings settings = appSettingsService.getGeneralSettings();
			ZoneId zoneId = parseZoneId(settings.getTimezone());
			LocalTime start = parseLocalTime(settings.getWorkdayStart(), LocalTime.of(9, 0));
			LocalTime end = parseLocalTime(settings.getWorkdayEnd(), LocalTime.of(18, 0));
			Set<DayOfWeek> workingDays = EnumSet.noneOf(DayOfWeek.class);
			if (Boolean.TRUE.equals(settings.getMondayEnabled())) {
				workingDays.add(DayOfWeek.MONDAY);
			}
			if (Boolean.TRUE.equals(settings.getTuesdayEnabled())) {
				workingDays.add(DayOfWeek.TUESDAY);
			}
			if (Boolean.TRUE.equals(settings.getWednesdayEnabled())) {
				workingDays.add(DayOfWeek.WEDNESDAY);
			}
			if (Boolean.TRUE.equals(settings.getThursdayEnabled())) {
				workingDays.add(DayOfWeek.THURSDAY);
			}
			if (Boolean.TRUE.equals(settings.getFridayEnabled())) {
				workingDays.add(DayOfWeek.FRIDAY);
			}
			if (Boolean.TRUE.equals(settings.getSaturdayEnabled())) {
				workingDays.add(DayOfWeek.SATURDAY);
			}
			if (Boolean.TRUE.equals(settings.getSundayEnabled())) {
				workingDays.add(DayOfWeek.SUNDAY);
			}
			return new AutomationTimeSettings(
					zoneId,
					Boolean.TRUE.equals(settings.getWorkingTimeEnabled()),
					start,
					end,
					workingDays
			);
		} catch (Exception ignored) {
			return AutomationTimeSettings.defaultSettings();
		}
	}


	private static ZoneId parseZoneId(String timezone) {
		if (timezone == null || timezone.isBlank()) {
			return ZoneId.systemDefault();
		}
		try {
			return ZoneId.of(timezone);
		} catch (Exception ignored) {
			return ZoneId.systemDefault();
		}
	}


	private static LocalTime parseLocalTime(String value, LocalTime fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return LocalTime.parse(value);
		} catch (Exception ignored) {
			return fallback;
		}
	}


	private static AutomationTimeSettings automationTimeSettings() {
		AutomationTimeSettings settings = CURRENT_TIME_SETTINGS.get();
		return settings == null ? AutomationTimeSettings.defaultSettings() : settings;
	}


	private static ZoneId automationZone() {
		return automationTimeSettings().zoneId();
	}


	private static ZonedDateTime automationNow() {
		return ZonedDateTime.now(automationZone());
	}


	private static ZonedDateTime inAutomationZone(ZonedDateTime dateTime) {
		if (dateTime == null) {
			return null;
		}
		return dateTime.withZoneSameInstant(automationZone());
	}


	private static boolean isWorkingHoursBySettings(ZonedDateTime dateTime) {
		if (dateTime == null) {
			dateTime = automationNow();
		}
		AutomationTimeSettings settings = automationTimeSettings();
		if (!settings.workingTimeEnabled()) {
			return true;
		}
		ZonedDateTime localDateTime = inAutomationZone(dateTime);
		if (localDateTime == null) {
			return true;
		}
		if (!settings.workingDays().contains(localDateTime.getDayOfWeek())) {
			return false;
		}
		LocalTime current = localDateTime.toLocalTime();
		return !current.isBefore(settings.workdayStart())
				&& current.isBefore(settings.workdayEnd());
	}


	private static boolean isWorkingDayBySettings(ZonedDateTime dateTime) {
		if (dateTime == null) {
			dateTime = automationNow();
		}

		AutomationTimeSettings settings = automationTimeSettings();

		if (!settings.workingTimeEnabled()) {
			return true;
		}

		ZonedDateTime localDateTime = inAutomationZone(dateTime);

		if (localDateTime == null) {
			return true;
		}

		return settings.workingDays().contains(localDateTime.getDayOfWeek());
	}


	private record AutomationTimeSettings(
			ZoneId zoneId,
			boolean workingTimeEnabled,
			LocalTime workdayStart,
			LocalTime workdayEnd,
			Set<DayOfWeek> workingDays
	) {
		static AutomationTimeSettings defaultSettings() {
			return new AutomationTimeSettings(
					ZoneId.systemDefault(),
					true,
					LocalTime.of(9, 0),
					LocalTime.of(18, 0),
					EnumSet.of(
							DayOfWeek.MONDAY,
							DayOfWeek.TUESDAY,
							DayOfWeek.WEDNESDAY,
							DayOfWeek.THURSDAY,
							DayOfWeek.FRIDAY
					)
			);
		}
	}


	/**
	 * Выполнить действия (как в UI поле "Действие").
	 * Поддерживает несколько команд через ';'
	 */
	public void executeActions(String actionScript, AutomationExecutionContext ctx) {
		if (actionScript == null || actionScript.isBlank()) {
			return;
		}
		List<String> commands = splitCommands(actionScript);
		for (String cmd : commands) {
			if (cmd.isBlank()) continue;
			JsonNode payloadRoot = ctx.getEvent().getPayload();
			ParsedCall call = parseCall(cmd.trim(), payloadRoot);
			if (call == null) continue;

			// actionType = "client.sendMessage"
			String actionType = call.target + "." + call.method;

			// actionNode: { "args": [...] }
			ObjectNode actionNode = mapper.createObjectNode();
			ArrayNode args = actionNode.putArray("args");
			for (Object arg : call.args) args.addPOJO(arg);

			actionExecutor.execute(actionType, actionNode, ctx);
		}
	}

	// ------------------------- ACTION PARSER -------------------------

	private static final Pattern CALL_PATTERN = Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\((.*)\\)$");
	private static final Pattern TEMPLATE_EXPRESSION_PATTERN = Pattern.compile("\\{\\{\\s*(.+?)\\s*}}");

	private ParsedCall parseCall(String script, JsonNode payloadRoot) {
		var matched = CALL_PATTERN.matcher(script);
		if (!matched.matches()) {
			return null;
		}
		String target = matched.group(1);
		String method = matched.group(2);
		String inside = matched.group(3).trim();
		List<Object> args = parseArgs(inside, payloadRoot);
		return new ParsedCall(target, method, args);
	}

	private List<Object> parseArgs(String inside, JsonNode payloadRoot) {
		if (inside.isBlank()) {
			return List.of();
		}
		List<Object> out = new ArrayList<>();
		for (String argument : splitTopLevel(inside, ',')) {
			if (!argument.isBlank()) {
				out.add(parseArg(argument.trim(), payloadRoot));
			}
		}
		return out;
	}

	private Object parseArg(String raw, JsonNode payloadRoot) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		raw = raw.trim();

		// В аргументах действий поддерживается конкатенация:
		// client.sendMessage('Заявка: ' + task.name + ' закрыта!')
		// Сначала разбираем оператор +, иначе вся строка ошибочно определяется
		// как один строковый литерал только потому, что начинается и заканчивается кавычкой.
		List<String> concatenationParts = splitTopLevel(raw, '+');
		if (concatenationParts.size() > 1) {
			StringBuilder result = new StringBuilder();
			for (String part : concatenationParts) {
				Object value = parseSingleArg(part.trim(), payloadRoot);
				if (value != null) {
					result.append(stringValue(value));
				}
			}
			return result.toString();
		}

		return parseSingleArg(raw, payloadRoot);
	}

	private Object parseSingleArg(String raw, JsonNode payloadRoot) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		raw = raw.trim();

		// Строковый шаблон. Внутри строки можно использовать {{ expression }}:
		// client.sendMessage('Заявка: {{ task.name.trim() }} закрыта!')
		if ((raw.startsWith("'") && raw.endsWith("'")) || (raw.startsWith("\"") && raw.endsWith("\""))) {
			String value = decodeActionStringLiteral(raw.substring(1, raw.length() - 1));
			return interpolateActionTemplate(value, payloadRoot);
		}

		// Отдельное шаблонное выражение также допустимо:
		// client.sendMessage({{ task.name.trim() }})
		String templateExpression = unwrapTemplateExpression(raw);
		if (templateExpression != null) {
			ActionValueResult result = tryEvaluateActionValueExpression(templateExpression, payloadRoot);
			return result.parsed() ? result.value() : raw;
		}

		if ("true".equalsIgnoreCase(raw)) {
			return true;
		}
		if ("false".equalsIgnoreCase(raw)) {
			return false;
		}
		if ("null".equalsIgnoreCase(raw)) {
			return null;
		}
		if (raw.matches("-?\\d+(\\.\\d+)?")) {
			try {
				return new BigDecimal(raw);
			} catch (Exception ignored) {
			}
		}

		// Для аргументов действий используется тот же expression parser, что и для условий.
		// Поэтому поддерживаются не только task.name, но и цепочки методов:
		// task.name.trim(), task.tags.last().name, message.text.lower().
		if (looksLikeActionValueExpression(raw)) {
			ActionValueResult result = tryEvaluateActionValueExpression(raw, payloadRoot);
			if (result.parsed()) {
				return result.value();
			}
		}

		// Некавыченные константы действий (например IN_PROGRESS) сохраняем строкой.
		return raw;
	}


	private static String decodeActionStringLiteral(String value) {
		if (value == null || value.isEmpty()) {
			return value;
		}

		// Внешний сценарий использует одинарные кавычки, поэтому \" внутри
		// является лишним экранированием. Старые версии frontend могли
		// экранировать его несколько раз: \\" -> \" -> ".
		while (value.contains("\\\"")) {
			value = value.replace("\\\"", "\"");
		}

		StringBuilder result = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char current = value.charAt(i);
			if (current != '\\' || i + 1 >= value.length()) {
				result.append(current);
				continue;
			}

			char next = value.charAt(++i);
			switch (next) {
				case '\\' -> result.append('\\');
				case '\'' -> result.append('\'');
				case '"' -> result.append('"');
				case 'n' -> result.append('\n');
				case 'r' -> result.append('\r');
				case 't' -> result.append('\t');
				default -> result.append('\\').append(next);
			}
		}
		return result.toString();
	}

	private String interpolateActionTemplate(String template, JsonNode payloadRoot) {
		Matcher matcher = TEMPLATE_EXPRESSION_PATTERN.matcher(template);
		StringBuffer result = new StringBuffer();
		boolean found = false;

		while (matcher.find()) {
			found = true;
			String expression = matcher.group(1) == null ? "" : matcher.group(1).trim();
			ActionValueResult evaluated = tryEvaluateActionValueExpression(expression, payloadRoot);

			String replacement;
			if (!evaluated.parsed()) {
				// Не скрываем ошибку в шаблоне: оставляем неизвестное выражение как было.
				replacement = matcher.group(0);
			} else if (evaluated.value() == null) {
				replacement = "";
			} else {
				replacement = stringValue(evaluated.value());
			}

			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}

		if (!found) {
			return template;
		}

		matcher.appendTail(result);
		return result.toString();
	}

	private String unwrapTemplateExpression(String raw) {
		if (raw == null) {
			return null;
		}
		String value = raw.trim();
		if (!value.startsWith("{{") || !value.endsWith("}}") || value.length() < 4) {
			return null;
		}
		return value.substring(2, value.length() - 2).trim();
	}

	private boolean looksLikeActionValueExpression(String raw) {
		if (raw == null || raw.isBlank()) {
			return false;
		}
		String value = raw.trim();
		return value.indexOf('.') > 0
				|| value.matches("[a-zA-Z_][a-zA-Z0-9_]*\\s*\\(.*\\)");
	}

	private ActionValueResult tryEvaluateActionValueExpression(String expression, JsonNode payloadRoot) {
		if (expression == null || expression.isBlank()) {
			return new ActionValueResult(false, null);
		}
		try {
			Lexer lexer = new Lexer(expression);
			Parser parser = new Parser(lexer);
			ExprNode parsed = parser.parseCompleteExpression();
			return new ActionValueResult(true, parsed.eval(payloadRoot));
		} catch (RuntimeException ignored) {
			return new ActionValueResult(false, null);
		}
	}

	private String stringValue(Object value) {
		if (value instanceof JsonNode node) {
			return node.isTextual() ? node.asText() : node.toString();
		}
		if (value instanceof BigDecimal number) {
			return number.stripTrailingZeros().toPlainString();
		}
		return String.valueOf(value);
	}

	private List<String> splitTopLevel(String value, char delimiter) {
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inString = false;
		char quote = 0;
		boolean escaped = false;
		int roundDepth = 0;
		int squareDepth = 0;
		int curlyDepth = 0;

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (inString) {
				current.append(c);
				if (escaped) {
					escaped = false;
				} else if (c == '\\') {
					escaped = true;
				} else if (c == quote) {
					inString = false;
				}
				continue;
			}

			if (c == '\'' || c == '"') {
				inString = true;
				quote = c;
				current.append(c);
				continue;
			}

			switch (c) {
				case '(' -> roundDepth++;
				case ')' -> roundDepth = Math.max(0, roundDepth - 1);
				case '[' -> squareDepth++;
				case ']' -> squareDepth = Math.max(0, squareDepth - 1);
				case '{' -> curlyDepth++;
				case '}' -> curlyDepth = Math.max(0, curlyDepth - 1);
				default -> {
				}
			}

			if (c == delimiter && roundDepth == 0 && squareDepth == 0 && curlyDepth == 0) {
				parts.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		parts.add(current.toString());
		return parts;
	}

	private JsonNode readByPath(JsonNode root, String path) {
		if (root == null || root.isNull() || path == null || path.isBlank()) {
			return null;
		}
		JsonNode cur = root;
		for (String p : path.split("\\.")) {
			if (cur == null || cur.isNull()) {
				return null;
			}
			cur = cur.get(p);
		}
		return cur;
	}

	private Object parseLiteral(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}

		// 'строка'
		if ((raw.startsWith("'") && raw.endsWith("'")) || (raw.startsWith("\"") && raw.endsWith("\""))) {
			String s = raw.substring(1, raw.length() - 1);
			return s.replace("\\'", "'").replace("\\\"", "\"");
		}

		// true/false/null
		if ("true".equalsIgnoreCase(raw)) {
			return true;
		}
		if ("false".equalsIgnoreCase(raw)) {
			return false;
		}
		if ("null".equalsIgnoreCase(raw)) {
			return null;
		}

		// число
		try {
			return new BigDecimal(raw);
		} catch (Exception ignored) {
		}

		// иначе строкой
		return raw;
	}

	private List<String> splitCommands(String script) {
		List<String> list = new ArrayList<>();
		StringBuilder sb = new StringBuilder();

		boolean inString = false;
		char quote = 0;

		for (int i = 0; i < script.length(); i++) {
			char c = script.charAt(i);
			if (inString) {
				sb.append(c);
				if (c == quote && script.charAt(i - 1) != '\\') inString = false;
				continue;
			}
			if (c == '\'' || c == '"') {
				inString = true;
				quote = c;
				sb.append(c);
				continue;
			}
			if (c == ';') {
				list.add(sb.toString().trim());
				sb.setLength(0);
				continue;
			}
			sb.append(c);
		}
		if (!sb.toString().trim().isBlank()) {
			list.add(sb.toString().trim());
		}
		return list;
	}

	private record ParsedCall(String target, String method, List<Object> args) {
	}

	private record ActionValueResult(boolean parsed, Object value) {
	}

	// ------------------------- EXPRESSION ENGINE -------------------------

	/**
	 * Токены
	 */
	private enum TokType {
		IDENT,
		NUMBER,
		STRING,
		TRUE,
		FALSE,
		NULL,
		OP,
		LPAREN,
		RPAREN,
		COMMA,
		DOT,
		LBRACKET,
		RBRACKET,
		EOF
	}

	private record Tok(TokType type, String text) {
	}

	private static final class Lexer {
		private final String s;
		private int i = 0;

		Lexer(String s) {
			this.s = s;
		}

		Tok next() {
			skipWs();
			if (i >= s.length()) {
				return new Tok(TokType.EOF, "");
			}
			char c = s.charAt(i);
			switch (c) {
				case '(' -> {
					i++;
					return new Tok(TokType.LPAREN, "(");
				}
				case ')' -> {
					i++;
					return new Tok(TokType.RPAREN, ")");
				}
				case ',' -> {
					i++;
					return new Tok(TokType.COMMA, ",");
				}
				case '.' -> {
					i++;
					return new Tok(TokType.DOT, ".");
				}
				case '[' -> {
					i++;
					return new Tok(TokType.LBRACKET, "[");
				}
				case ']' -> {
					i++;
					return new Tok(TokType.RBRACKET, "]");
				}
				case '\'', '\"' -> {
					int start = i;
					i++;
					while (i < s.length()) {
						char cc = s.charAt(i);
						if (cc == c && s.charAt(i - 1) != '\\') {
							i++;
							break;
						}
						i++;
					}
					return new Tok(TokType.STRING, s.substring(start, i));
				}
			}

			// operator: && || >= <= > < = ! in
			if (startsWith("&")) {
				i++;
				return new Tok(TokType.OP, "and");
			}
			if (startsWith("|")) {
				i++;
				return new Tok(TokType.OP, "or");
			}
			if (startsWithWord("and")) {
				i += 3;
				return new Tok(TokType.OP, "and");
			}
			if (startsWithWord("or")) {
				i += 2;
				return new Tok(TokType.OP, "or");
			}
			if (startsWith(">=") || startsWith("<=")) {
				String op = s.substring(i, i + 2);
				i += 2;
				return new Tok(TokType.OP, op);
			}
			if (c == '>' || c == '<' || c == '=' || c == '!') {
				i++;
				return new Tok(TokType.OP, String.valueOf(c));
			}

			// number
			if (Character.isDigit(c)) {
				int start = i;
				while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
				return new Tok(TokType.NUMBER, s.substring(start, i));
			}

			// ident / keyword / "in"
			if (Character.isLetter(c) || c == '_') {
				int start = i;
				while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
					i++;
				}
				String w = s.substring(start, i);
				if ("true".equalsIgnoreCase(w)) {
					return new Tok(TokType.TRUE, w);
				}
				if ("false".equalsIgnoreCase(w)) {
					return new Tok(TokType.FALSE, w);
				}
				if ("null".equalsIgnoreCase(w)) {
					return new Tok(TokType.NULL, w);
				}
				if ("in".equalsIgnoreCase(w)) {
					return new Tok(TokType.OP, "in");
				}
				return new Tok(TokType.IDENT, w);
			}
			// неизвестный символ
			i++;
			return new Tok(TokType.OP, String.valueOf(c));
		}

		private boolean startsWith(String x) {
			return s.regionMatches(i, x, 0, x.length());
		}

		private boolean startsWithWord(String w) {
			if (!s.regionMatches(true, i, w, 0, w.length())) return false;
			int end = i + w.length();
			if (end >= s.length()) return true;
			char next = s.charAt(end);
			return !Character.isLetterOrDigit(next) && next != '_';
		}

		private void skipWs() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
		}
	}

	// AST
	private interface ExprNode {
		Object eval(JsonNode ctx);
	}

	private static final class Parser {
		private final Lexer lx;
		private Tok cur;

		Parser(Lexer lx) {
			this.lx = lx;
			this.cur = lx.next();
		}

		ExprNode parseExpression() {
			return parseOr();
		}

		ExprNode parseCompleteExpression() {
			ExprNode expression = parseExpression();
			expect(TokType.EOF);
			return expression;
		}

		private ExprNode parseOr() {
			ExprNode left = parseAnd();
			while (cur.type == TokType.OP && "or".equals(cur.text)) {
				consume();
				ExprNode right = parseAnd();
				ExprNode finalLeft = left;
				left = ctx -> asBool(finalLeft.eval(ctx)) || asBool(right.eval(ctx));
			}
			return left;
		}

		private ExprNode parseAnd() {
			ExprNode left = parseNot();
			while (cur.type == TokType.OP && "and".equals(cur.text)) {
				consume();
				ExprNode right = parseNot();
				ExprNode finalLeft = left;
				left = ctx -> asBool(finalLeft.eval(ctx)) && asBool(right.eval(ctx));
			}
			return left;
		}

		private ExprNode parseNot() {
			if (cur.type == TokType.OP && "!".equals(cur.text)) {
				consume();
				ExprNode inner = parseNot();
				return ctx -> !asBool(inner.eval(ctx));
			}
			return parseCompare();
		}

		private ExprNode parseCompare() {
			ExprNode left = parsePrimary();

			if (cur.type == TokType.OP) {
				String opText = cur.text;

				if (isCompareOp(opText)) {
					consume();
					ExprNode right = parsePrimary();
					TriggerOperationType op = mapOp(opText);

					return ctx -> evalCompare(op, left.eval(ctx), right.eval(ctx));
				}
			}

			return left;
		}

		private ExprNode parsePrimary() {
			// (expr)
			if (cur.type == TokType.LPAREN) {
				consume();
				ExprNode e = parseExpression();
				expect(TokType.RPAREN);
				consume();
				return e;
			}

			// list: [a,b,c]
			if (cur.type == TokType.LBRACKET) {
				consume();
				List<ExprNode> items = new ArrayList<>();
				if (cur.type != TokType.RBRACKET) {
					items.add(parseExpression());
					while (cur.type == TokType.COMMA) {
						consume();
						items.add(parseExpression());
					}
				}
				expect(TokType.RBRACKET);
				consume();
				return ctx -> {
					List<Object> v = new ArrayList<>();
					for (ExprNode n : items) v.add(n.eval(ctx));
					return v;
				};
			}

			// literal
			if (cur.type == TokType.STRING) {
				String raw = cur.text;
				consume();
				return ctx -> unquote(raw);
			}
			if (cur.type == TokType.NUMBER) {
				String raw = cur.text;
				consume();
				return ctx -> new BigDecimal(raw);
			}
			if (cur.type == TokType.TRUE) {
				consume();
				return ctx -> true;
			}
			if (cur.type == TokType.FALSE) {
				consume();
				return ctx -> false;
			}
			if (cur.type == TokType.NULL) {
				consume();
				return ctx -> null;
			}

						// ident: function(...) OR path OR rust-like method call:
			// contains(message.text, 'x')
			// message.text.contains('x')
			// message.text.lower().contains('x')
			if (cur.type == TokType.IDENT) {
				String name = cur.text;
				consume();

				// function call: contains(message.text, 'ошибка')
				if (cur.type == TokType.LPAREN) {
					List<ExprNode> args = parseArgumentList();

					TriggerFunctionsType fn = mapFn(name);
					return ctx -> {
						Object target = args.isEmpty() ? null : args.getFirst().eval(ctx);
						List<Object> rest = new ArrayList<>();
						for (int i = 1; i < args.size(); i++) {
							rest.add(args.get(i).eval(ctx));
						}
						return evalFunction(fn, target, rest, ctx);
					};
				}

				// path base: message.text / client.openTasks / task.tags
				List<PathSeg> segs = new ArrayList<>();
				segs.add(new PathSeg(name, false));

				ExprNode current = null;

				while (cur.type == TokType.DOT) {
					consume();
					expect(TokType.IDENT);
					String part = cur.text;
					consume();

					// method call over current value:
					// message.text.contains('x')
					// task.tags.hasTag('VIP')
					// message.text.lower()
					if (cur.type == TokType.LPAREN) {
						List<ExprNode> args = parseArgumentList();

						ExprNode receiver;
						if (current != null) {
							receiver = current;
						} else {
							List<PathSeg> frozenPath = new ArrayList<>(segs);
							receiver = ctx -> resolvePath(ctx, frozenPath);
						}

						if ("size".equalsIgnoreCase(part)) {
							current = ctx -> sizeOf(receiver.eval(ctx));
							continue;
						}

						if ("last".equalsIgnoreCase(part)) {
							current = ctx -> lastOf(receiver.eval(ctx));
							continue;
						}

						TriggerFunctionsType fn = mapFn(part);

						current = ctx -> {
							Object target = receiver.eval(ctx);

							List<Object> rest = new ArrayList<>();
							for (ExprNode arg : args) {
								rest.add(arg.eval(ctx));
							}

							return evalFunction(fn, target, rest, ctx);
						};

						continue;
					}

					// property access
					if (current != null) {
						ExprNode previous = current;
						current = ctx -> readProperty(previous.eval(ctx), part);
					} else {
						segs.add(new PathSeg(part, false));
					}
				}

				if (current != null) {
					return current;
				}

				List<PathSeg> frozenPath = new ArrayList<>(segs);
				return ctx -> resolvePath(ctx, frozenPath);
			}

			// fallback
			consume();
			return ctx -> null;
		}

		private void expect(TokType t) {
			if (cur.type != t) {
				throw new IllegalArgumentException("Parse error: expected " + t + " but got " + cur.type + " (" + cur.text + ")");
			}
		}

		private void consume() {
			cur = lx.next();
		}

		private boolean isCompareOp(String x) {
			return "=".equals(x) || ">".equals(x) || ">=".equals(x) || "<".equals(x) || "<=".equals(x) || "in".equalsIgnoreCase(x);
		}

		private TriggerOperationType mapOp(String x) {
			for (TriggerOperationType t : TriggerOperationType.values()) {
				if (t.getOperator().equalsIgnoreCase(x)) {
					return t;
				}
			}
			return TriggerOperationType.EQ;
		}

		private TriggerFunctionsType mapFn(String x) {
			// поддержка: starts_with / STARTS_WITH
			for (TriggerFunctionsType t : TriggerFunctionsType.values()) {
				if (t.name().equalsIgnoreCase(x) || t.getOperator().equalsIgnoreCase(x)) {
					return t;
				}
			}
			throw new IllegalArgumentException("Unknown function: " + x);
		}

		private List<ExprNode> parseArgumentList() {
			expect(TokType.LPAREN);
			consume();

			List<ExprNode> args = new ArrayList<>();

			if (cur.type != TokType.RPAREN) {
				args.add(parseExpression());

				while (cur.type == TokType.COMMA) {
					consume();
					args.add(parseExpression());
				}
			}

			expect(TokType.RPAREN);
			consume();

			return args;
		}

		private record PathSeg(String key, boolean isSize) {
		}
	}

	// ------------------------- EVAL HELPERS -------------------------

	private static Object resolvePath(JsonNode root, List<?> segsRaw) {
		@SuppressWarnings("unchecked")
		List<Parser.PathSeg> segs = (List<Parser.PathSeg>) segsRaw;

		JsonNode cur = root;
		for (Parser.PathSeg s : segs) {
			if (s.isSize()) {
				// size() над текущим значением
				return sizeOf(cur);
			}

			if (cur == null || cur.isNull()) {
				return null;
			}
			cur = cur.get(s.key());
		}

		return unwrap(cur);
	}

	private static Object readProperty(Object source, String property) {
		if (source == null || property == null || property.isBlank()) {
			return null;
		}
		if (source instanceof JsonNode node) {
			JsonNode child = node.get(property);
			return unwrap(child);
		}
		if (source instanceof Map<?, ?> map) {
			return map.get(property);
		}
		return null;
	}

	private static Object sizeOf(Object value) {
		switch (value) {
			case null -> {
				return 0;
			}
			case JsonNode node -> {
				if (node.isNull()) {
					return 0;
				}
				if (node.isArray()) {
					return node.size();
				}
				if (node.isTextual()) {
					return node.asText().length();
				}
				if (node.isObject()) {
					return node.size();
				}
				return 0;
			}
			case CharSequence s -> {
				return s.length();
			}
			case Collection<?> c -> {
				return c.size();
			}
			case Map<?, ?> m -> {
				return m.size();
			}
			default -> {
			}
		}
		return 0;
	}

	private static Object lastOf(Object value) {
		switch (value) {
			case null -> {
				return null;
			}
			case JsonNode node -> {
				if (node.isArray()) {
					if (node.isEmpty()) {
						return null;
					}
					return unwrap(node.get(node.size() - 1));
				}
				if (node.isTextual()) {
					String s = node.asText();
					return s.isEmpty() ? null : String.valueOf(s.charAt(s.length() - 1));
				}
				return null;
			}
			case List<?> list -> {
				if (list.isEmpty()) {
					return null;
				}
				return list.get(list.size() - 1);
			}
			case Collection<?> collection -> {
				if (collection.isEmpty()) {
					return null;
				}
				Object last = null;
				for (Object item : collection) {
					last = item;
				}
				return last;
			}
			case CharSequence s -> {
				if (s.isEmpty()) {
					return null;
				}
				return String.valueOf(s.charAt(s.length() - 1));
			}
			default -> {
			}
		}
		return null;
	}

	private static Object unwrap(JsonNode n) {
		if (n == null || n.isNull()) {
			return null;
		}
		if (n.isTextual()) {
			return n.asText();
		}
		if (n.isBoolean()) {
			return n.asBoolean();
		}
		if (n.isNumber()) {
			return n.decimalValue();
		}
		if (n.isArray()) {
			List<Object> list = new ArrayList<>();
			for (JsonNode x : n) list.add(unwrap(x));
			return list;
		}
		return n; // object как JsonNode
	}

	private static String unquote(String s) {
		if (s == null || s.length() < 2) {
			return s;
		}
		char q = s.charAt(0);
		if ((q == '\'' || q == '"') && s.charAt(s.length() - 1) == q) {
			return decodeActionStringLiteral(s.substring(1, s.length() - 1));
		}
		return s;
	}

	private static boolean evalCompare(TriggerOperationType op, Object left, Object right) {
		return switch (op) {
			case EQ -> Objects.equals(norm(left), norm(right));
			case GT -> cmp(left, right) > 0;
			case GTE -> cmp(left, right) >= 0;
			case LT -> cmp(left, right) < 0;
			case LTE -> cmp(left, right) <= 0;
			case IN -> inOp(left, right);
			default -> false;
		};
	}

	private static boolean inOp(Object left, Object right) {
		if (right == null) {
			return false;
		}
		Set<Object> set = new HashSet<>();
		if (right instanceof Collection<?> col) {
			for (Object x : col) set.add(norm(x));
		} else {
			set.add(norm(right));
		}
		if (left instanceof Collection<?> colL) {
			for (Object x : colL) {
				if (set.contains(norm(x))) {
					return true;
				}
			}
			return false;
		}
		return set.contains(norm(left));
	}

	private static int cmp(Object a, Object b) {
		BigDecimal aa = toBigDecimal(a);
		BigDecimal bb = toBigDecimal(b);
		if (aa == null || bb == null) {
			return -999;
		}
		return aa.compareTo(bb);
	}

	private static BigDecimal toBigDecimal(Object v) {
		switch (v) {
			case null -> {
				return null;
			}
			case BigDecimal bd -> {
				return bd;
			}
			case Number n -> {
				return new BigDecimal(n.toString());
			}
			case String s -> {
				try {
					return new BigDecimal(s.trim());
				} catch (Exception ignored) {
					return null;
				}
			}
			default -> {
			}
		}
		return null;
	}

	private static Object norm(Object v) {
		return switch (v) {
			case null -> null;
			case BigDecimal bd -> bd.stripTrailingZeros();
			case Number n -> new BigDecimal(n.toString()).stripTrailingZeros();
			case String s -> s.trim();
			default -> v;
		};
	}

	private static boolean asBool(Object v) {
		return switch (v) {
			case null -> false;
			case Boolean b -> b;
			case Number n -> new BigDecimal(n.toString()).compareTo(BigDecimal.ZERO) != 0;
			case String s -> !s.isBlank() && !"false".equalsIgnoreCase(s);
			default -> true;
		};
	}

	private static Object evalFunction(TriggerFunctionsType fn, Object target, List<Object> args, JsonNode root) {
		return switch (fn) {
			case STARTS_WITH -> {
				String s = toStr(target);
				String pref = args.isEmpty() ? null : toStr(args.getFirst());
				yield s != null && pref != null && s.startsWith(pref);
			}
			case ENDS_WITH -> {
				String s = toStr(target);
				String suf = args.isEmpty() ? null : toStr(args.getFirst());
				yield s != null && suf != null && s.endsWith(suf);
			}

			case CONTAINS -> {
				String s = toStr(target);
				String part = args.isEmpty() ? null : toStr(args.getFirst());
				yield containsIgnoreCase(s, part);
			}

			case CONTAINS_ANY -> containsAny(target, args);

			case CONTAINS_ALL -> containsAll(target, args);

			case NAME_CONTAINS -> collectionFieldContains(target, "name", firstArg(args));

			case TEXT_CONTAINS -> collectionFieldContains(target, "text", firstArg(args));

			case DESCRIPTION_CONTAINS -> collectionFieldContains(target, "description", firstArg(args));

			case FIELD_CONTAINS -> collectionFieldContains(target, toStr(firstArg(args)), argAt(args, 1));

			case FIELD_EQUALS -> collectionFieldEquals(target, toStr(firstArg(args)), argAt(args, 1));

			case FIELD_EXISTS -> collectionFieldExists(target, toStr(firstArg(args)));

			case STATUS_IS -> collectionEntityFieldMatches(target, "status", firstArg(args));

			case PRIORITY_IS -> collectionEntityFieldMatches(target, "priority", firstArg(args));

			case TYPE_IS -> collectionEntityFieldMatches(target, "type", firstArg(args));

			case SUPPORT_LINE_IS -> collectionEntityFieldMatches(target, "supportLine", firstArg(args));

			case ASSIGNED_TO -> collectionEntityFieldMatches(target, "executor", firstArg(args));

			case HAS_OPEN -> countMatching(target, AutomationScriptRuntime::isOpenTaskItem) > 0;

			case HAS_CLOSED -> countMatching(target, AutomationScriptRuntime::isCompletedItem) > 0;

			case OPEN_COUNT -> countMatching(target, AutomationScriptRuntime::isOpenTaskItem);

			case CLOSED_COUNT -> countMatching(target, AutomationScriptRuntime::isCompletedItem);

			case OVERDUE_COUNT -> countMatching(target, AutomationScriptRuntime::isOverdueItem);

			case UNASSIGNED_COUNT -> countMatching(target, item -> !hasAssigneeItem(item));

			case INCOMING_TEXT_CONTAINS -> collectionFieldContainsWhere(
					target, "text", firstArg(args), AutomationScriptRuntime::isIncomingMessageItem
			);

			case OUTGOING_TEXT_CONTAINS -> collectionFieldContainsWhere(
					target, "text", firstArg(args), AutomationScriptRuntime::isOutgoingMessageItem
			);

			case COMMENT_CONTAINS -> collectionFieldContainsWhere(
					target, "text", firstArg(args), AutomationScriptRuntime::isCommentMessageItem
			);

			case INCOMING_COUNT -> countMatching(target, AutomationScriptRuntime::isIncomingMessageItem);

			case OUTGOING_COUNT -> countMatching(target, AutomationScriptRuntime::isOutgoingMessageItem);

			case UNREAD_COUNT -> countMatching(target, AutomationScriptRuntime::isUnreadMessageItem);

			case ATTACHMENT_COUNT -> countMatching(target, AutomationScriptRuntime::hasAttachmentItem);

			case HAS_INCOMPLETE -> countMatching(target, item -> !isCompletedItem(item)) > 0;

			case COMPLETED_COUNT -> countMatching(target, AutomationScriptRuntime::isCompletedItem);

			case INCOMPLETE_COUNT -> countMatching(target, item -> !isCompletedItem(item));

			case HAS_ASSIGNEE -> hasAssigneeItem(target);

			case HAS_DEADLINE -> hasDeadlineItem(target);

			case IS_OVERDUE -> isOverdueItem(target);

			case IS_COMPLETED -> isCompletedItem(target);

			case LOWER -> {
				String s = toStr(target);
				yield s == null ? null : s.toLowerCase(Locale.ROOT);
			}

			case UPPER -> {
				String s = toStr(target);
				yield s == null ? null : s.toUpperCase(Locale.ROOT);
			}

			case TRIM -> {
				String s = toStr(target);
				yield s == null ? null : s.trim();
			}

			case LENGTH -> sizeOf(target);

			case LAST -> lastOf(target);

			case EQUALS_IGNORE_CASE -> {
				String left = toStr(target);
				String right = args.isEmpty() ? null : toStr(args.getFirst());
				yield left != null && right != null && left.equalsIgnoreCase(right);
			}

			case MATCHES -> {
				String text = toStr(target);
				String regex = args.isEmpty() ? null : toStr(args.getFirst());
				yield matchesRegex(text, regex);
			}

			case CONTAINS_REGEX -> {
				String text = toStr(target);
				String regex = args.isEmpty() ? null : toStr(args.getFirst());
				yield containsRegex(text, regex);
			}

			case DAYS_BETWEEN -> {
				ZonedDateTime from = toZonedDateTime(target);
				ZonedDateTime to = args.isEmpty() ? null : toZonedDateTime(args.getFirst());

				if (from == null || to == null) {
					yield null;
				}

				yield Duration.between(from, to).toDays();
			}

			case MINUTES_BETWEEN -> {
				ZonedDateTime from = toZonedDateTime(target);
				ZonedDateTime to = args.isEmpty() ? null : toZonedDateTime(args.getFirst());

				if (from == null || to == null) {
					yield null;
				}

				yield Duration.between(from, to).toMinutes();
			}

			case DAYS_SINCE -> {
				ZonedDateTime from = toZonedDateTime(target);
				if (from == null) {
					yield null;
				}
				yield Duration.between(from, automationNow()).toDays();
			}

			case MINUTES_SINCE -> {
				ZonedDateTime from = toZonedDateTime(target);
				if (from == null) {
					yield null;
				}
				yield Duration.between(from, automationNow()).toMinutes();
			}

			case HAS_TAG -> {
				String tagName = args.isEmpty() ? null : toStr(args.getFirst());
				yield hasTag(target, tagName);
			}

			case NO_OPEN_TASKS -> noOpenTasks(root);

			case HAS_OPEN_TASKS -> !noOpenTasks(root);

			case OPEN_TASKS_COUNT -> sizeOf(readByPathStatic(root, "client.openTasks"));

			case MESSAGES_COUNT -> sizeOf(readByPathStatic(root, "client.messages"));

			case INCOME_MESSAGES_COUNT -> sizeOf(readByPathStatic(root, "client.incomeMessages"));

			case OUTCOME_MESSAGES_COUNT -> sizeOf(readByPathStatic(root, "client.outcomeMessages"));

			case IS_FIRST_MESSAGE -> {
				Object count = sizeOf(readByPathStatic(root, "client.incomeMessages"));
				yield toBigDecimal(count) != null && toBigDecimal(count).compareTo(BigDecimal.ONE) == 0;
			}

			case IS_REPEAT_MESSAGE -> {
				Object count = sizeOf(readByPathStatic(root, "client.incomeMessages"));
				BigDecimal value = toBigDecimal(count);
				yield value != null && value.compareTo(BigDecimal.ONE) > 0;
			}

			case HAS_ATTACHMENT -> hasAttachment(root, target);

			case IS_IMAGE -> isImage(target);

			case IS_DOCUMENT -> isDocument(target);

			case IS_TELEGRAM -> messageFromEquals(root, target, "TELEGRAM");

			case IS_EMAIL -> messageFromEquals(root, target, "EMAIL");

			case IS_WHATSAPP -> messageFromEquals(root, target, "WHATSAPP");

			case IS_TODAY -> {
				ZonedDateTime dateTime = inAutomationZone(toZonedDateTime(target));
				if (dateTime == null) {
					yield false;
				}
				yield dateTime.toLocalDate().equals(automationNow().toLocalDate());
			}

			case IS_BEFORE -> {
				ZonedDateTime left = toZonedDateTime(target);
				ZonedDateTime right = args.isEmpty() ? null : toZonedDateTime(args.getFirst());
				yield left != null && right != null && left.isBefore(right);
			}

			case IS_AFTER -> {
				ZonedDateTime left = toZonedDateTime(target);
				ZonedDateTime right = args.isEmpty() ? null : toZonedDateTime(args.getFirst());
				yield left != null && right != null && left.isAfter(right);
			}

			case ANY_OF -> anyOf(target, args);

			case NONE_OF -> !anyOf(target, args);

			case ALL_OF -> allOf(target, args);

			case IS_NULL -> target == null;

			case NOT_NULL -> target != null;

			case IS_EMPTY -> isEmpty(target);

			case NOT_EMPTY -> !isEmpty(target);

			case IS_TRUE -> asBool(target);

			case IS_FALSE -> !asBool(target);

			case NOW -> automationNow();

			case HOUR -> {
				ZonedDateTime dateTime = inAutomationZone(toZonedDateTime(target));
				yield dateTime == null ? null : dateTime.getHour();
			}

			case DAY_OF_WEEK -> {
				ZonedDateTime dateTime = inAutomationZone(toZonedDateTime(target));
				yield dateTime == null ? null : dateTime.getDayOfWeek().getValue();
			}

			case IS_WEEKEND -> {
				ZonedDateTime dateTime = inAutomationZone(toZonedDateTime(target));
				if (dateTime == null) {
					dateTime = automationNow();
				}
				yield !isWorkingDayBySettings(dateTime);
			}

			case IS_WORKING_HOURS -> {
				ZonedDateTime dateTime = inAutomationZone(toZonedDateTime(target));
				if (dateTime == null) {
					dateTime = automationNow();
				}
				yield isWorkingHoursBySettings(dateTime);
			}

			case IS_AFTER_HOURS -> {
				ZonedDateTime dateTime = inAutomationZone(toZonedDateTime(target));
				if (dateTime == null) {
					dateTime = automationNow();
				}
				yield !isWorkingHoursBySettings(dateTime);
			}
		};
	}



	private static Object firstArg(List<Object> args) {
		return argAt(args, 0);
	}


	private static Object argAt(List<Object> args, int index) {
		return args == null || index < 0 || index >= args.size() ? null : args.get(index);
	}


	private static boolean collectionFieldContains(Object target, String fieldPath, Object expected) {
		return collectionFieldContainsWhere(target, fieldPath, expected, item -> true);
	}


	private static boolean collectionFieldContainsWhere(
			Object target,
			String fieldPath,
			Object expected,
			Predicate<Object> filter
	) {
		String part = scalarText(expected);
		if (fieldPath == null || fieldPath.isBlank() || part == null) {
			return false;
		}
		for (Object item : itemsOf(target)) {
			if (filter != null && !filter.test(item)) {
				continue;
			}
			String value = scalarText(readObjectPath(item, fieldPath));
			if (containsIgnoreCase(value, part)) {
				return true;
			}
		}
		return false;
	}


	private static boolean collectionFieldEquals(Object target, String fieldPath, Object expected) {
		if (fieldPath == null || fieldPath.isBlank()) {
			return false;
		}
		for (Object item : itemsOf(target)) {
			if (valueMatches(readObjectPath(item, fieldPath), expected)) {
				return true;
			}
		}
		return false;
	}


	private static boolean collectionFieldExists(Object target, String fieldPath) {
		if (fieldPath == null || fieldPath.isBlank()) {
			return false;
		}
		for (Object item : itemsOf(target)) {
			Object value = readObjectPath(item, fieldPath);
			if (value != null && !isEmptyValue(value)) {
				return true;
			}
		}
		return false;
	}


	private static boolean collectionEntityFieldMatches(Object target, String fieldPath, Object expected) {
		if (expected == null) {
			return false;
		}
		for (Object item : itemsOf(target)) {
			Object value = readObjectPath(item, fieldPath);
			if (entityMatches(value, expected)) {
				return true;
			}
		}
		return false;
	}


	private static long countMatching(Object target, Predicate<Object> predicate) {
		if (predicate == null) {
			return 0L;
		}
		long count = 0L;
		for (Object item : itemsOf(target)) {
			if (predicate.test(item)) {
				count++;
			}
		}
		return count;
	}


	private static List<Object> itemsOf(Object target) {
		if (target == null) {
			return List.of();
		}
		if (target instanceof JsonNode node) {
			if (node.isNull() || node.isMissingNode()) {
				return List.of();
			}
			if (node.isArray()) {
				List<Object> items = new ArrayList<>(node.size());
				for (JsonNode item : node) {
					items.add(item);
				}
				return items;
			}
			return List.of(node);
		}
		if (target instanceof Collection<?> collection) {
			return new ArrayList<>(collection);
		}
		if (target.getClass().isArray()) {
			int length = java.lang.reflect.Array.getLength(target);
			List<Object> items = new ArrayList<>(length);
			for (int index = 0; index < length; index++) {
				items.add(java.lang.reflect.Array.get(target, index));
			}
			return items;
		}
		return List.of(target);
	}


	private static Object readObjectPath(Object source, String path) {
		if (source == null || path == null || path.isBlank()) {
			return null;
		}
		Object current = source;
		for (String part : path.split("\\.")) {
			if (current == null) {
				return null;
			}
			if (current instanceof JsonNode node) {
				if (!node.isObject()) {
					return null;
				}
				current = unwrap(node.get(part));
				continue;
			}
			if (current instanceof Map<?, ?> map) {
				current = map.get(part);
				continue;
			}
			return null;
		}
		return current;
	}


	private static Object readFirstObjectPath(Object source, String... paths) {
		if (paths == null) {
			return null;
		}
		for (String path : paths) {
			Object value = readObjectPath(source, path);
			if (value != null) {
				return value;
			}
		}
		return null;
	}


	private static boolean entityMatches(Object value, Object expected) {
		if (value == null || expected == null) {
			return false;
		}
		if (valueMatches(value, expected)) {
			return true;
		}
		for (String property : List.of("id", "name", "type", "title", "username", "email")) {
			Object nested = readObjectPath(value, property);
			if (nested != null && valueMatches(nested, expected)) {
				return true;
			}
		}
		return false;
	}


	private static boolean valueMatches(Object left, Object right) {
		if (left == null || right == null) {
			return left == right;
		}
		BigDecimal leftNumber = toBigDecimal(left);
		BigDecimal rightNumber = toBigDecimal(right);
		if (leftNumber != null && rightNumber != null) {
			return leftNumber.compareTo(rightNumber) == 0;
		}
		String leftText = scalarText(left);
		String rightText = scalarText(right);
		return leftText != null && rightText != null && leftText.trim().equalsIgnoreCase(rightText.trim());
	}


	private static String scalarText(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof JsonNode node) {
			if (node.isNull() || node.isMissingNode()) {
				return null;
			}
			if (node.isValueNode()) {
				return node.asText();
			}
			return node.toString();
		}
		if (value instanceof BigDecimal number) {
			return number.stripTrailingZeros().toPlainString();
		}
		return String.valueOf(value);
	}


	private static boolean isEmptyValue(Object value) {
		if (value == null) {
			return true;
		}
		if (value instanceof JsonNode node) {
			return node.isNull()
					|| node.isMissingNode()
					|| node.isTextual() && node.asText().isBlank()
					|| node.isContainerNode() && node.isEmpty();
		}
		if (value instanceof CharSequence text) {
			return text.toString().isBlank();
		}
		if (value instanceof Collection<?> collection) {
			return collection.isEmpty();
		}
		if (value instanceof Map<?, ?> map) {
			return map.isEmpty();
		}
		return false;
	}


	private static boolean isCompletedItem(Object item) {
		return booleanValue(readObjectPath(item, "completed"), false);
	}


	private static boolean isOpenTaskItem(Object item) {
		return item != null && !isCompletedItem(item);
	}


	private static boolean hasAssigneeItem(Object item) {
		Object executor = readObjectPath(item, "executor");
		if (executor == null || isEmptyValue(executor)) {
			return false;
		}
		Object id = readObjectPath(executor, "id");
		return id == null || !isEmptyValue(id);
	}


	private static boolean hasDeadlineItem(Object item) {
		return toZonedDateTime(readObjectPath(item, "deadline")) != null;
	}


	private static boolean isOverdueItem(Object item) {
		if (item == null || isCompletedItem(item)) {
			return false;
		}
		ZonedDateTime deadline = inAutomationZone(toZonedDateTime(readObjectPath(item, "deadline")));
		return deadline != null && deadline.isBefore(automationNow());
	}


	private static boolean isActiveMessageItem(Object item) {
		return item != null && !booleanValue(readObjectPath(item, "deleted"), false);
	}


	private static boolean isIncomingMessageItem(Object item) {
		return isActiveMessageItem(item)
				&& !booleanValue(readFirstObjectPath(item, "isComment", "comment"), false)
				&& !booleanValue(readFirstObjectPath(item, "isSent", "sent"), false);
	}


	private static boolean isOutgoingMessageItem(Object item) {
		return isActiveMessageItem(item)
				&& !booleanValue(readFirstObjectPath(item, "isComment", "comment"), false)
				&& booleanValue(readFirstObjectPath(item, "isSent", "sent"), false);
	}


	private static boolean isCommentMessageItem(Object item) {
		return isActiveMessageItem(item)
				&& booleanValue(readFirstObjectPath(item, "isComment", "comment"), false);
	}


	private static boolean isUnreadMessageItem(Object item) {
		return isActiveMessageItem(item)
				&& !booleanValue(readFirstObjectPath(item, "isRead", "read"), false);
	}


	private static boolean hasAttachmentItem(Object item) {
		return !isEmptyValue(readObjectPath(item, "fileUuid"))
				|| !isEmptyValue(readObjectPath(item, "fileName"))
				|| !isEmptyValue(readObjectPath(item, "fileType"));
	}


	private static boolean booleanValue(Object value, boolean fallback) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof JsonNode node && node.isBoolean()) {
			return node.asBoolean();
		}
		if (value instanceof Number number) {
			return number.intValue() != 0;
		}
		if (value instanceof CharSequence text) {
			String normalized = text.toString().trim();
			if ("true".equalsIgnoreCase(normalized) || "1".equals(normalized)) {
				return true;
			}
			if ("false".equalsIgnoreCase(normalized) || "0".equals(normalized)) {
				return false;
			}
		}
		return fallback;
	}

	private static boolean containsIgnoreCase(String source, String part) {
		if (source == null || part == null) {
			return false;
		}

		return source.toLowerCase(Locale.ROOT)
				.contains(part.toLowerCase(Locale.ROOT));
	}

	private static boolean containsAny(Object target, List<Object> args) {
		String source = toStr(target);
		if (source == null || args == null || args.isEmpty()) {
			return false;
		}

		for (Object arg : args) {
			if (containsIgnoreCase(source, toStr(arg))) {
				return true;
			}
		}

		return false;
	}

	private static boolean containsAll(Object target, List<Object> args) {
		String source = toStr(target);
		if (source == null || args == null || args.isEmpty()) {
			return false;
		}

		for (Object arg : args) {
			if (!containsIgnoreCase(source, toStr(arg))) {
				return false;
			}
		}

		return true;
	}

	private static boolean matchesRegex(String text, String regex) {
		if (text == null || regex == null || regex.isBlank()) {
			return false;
		}

		try {
			return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
					.matcher(text)
					.matches();
		} catch (Exception ignored) {
			return false;
		}
	}

	private static boolean containsRegex(String text, String regex) {
		if (text == null || regex == null || regex.isBlank()) {
			return false;
		}

		try {
			return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
					.matcher(text)
					.find();
		} catch (Exception ignored) {
			return false;
		}
	}

	private static boolean hasAttachment(JsonNode root, Object target) {
		if (target != null) {
			return countMatching(target, AutomationScriptRuntime::hasAttachmentItem) > 0;
		}
		JsonNode messageNode = readByPathStatic(root, "message");
		return messageNode != null && !messageNode.isNull() && hasAttachmentItem(messageNode);
	}

	private static boolean hasNonBlankField(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && !value.isNull() && !value.asText("").isBlank();
	}

	private static boolean isImage(Object target) {
		String value = extractFileTypeOrName(target);
		if (value == null) {
			return false;
		}

		String s = value.toLowerCase(Locale.ROOT);

		return s.startsWith("image/")
				|| s.endsWith(".jpg")
				|| s.endsWith(".jpeg")
				|| s.endsWith(".png")
				|| s.endsWith(".gif")
				|| s.endsWith(".webp")
				|| s.endsWith(".bmp");
	}

	private static boolean isDocument(Object target) {
		String value = extractFileTypeOrName(target);
		if (value == null) {
			return false;
		}

		String s = value.toLowerCase(Locale.ROOT);

		return s.equals("application/pdf")
				|| s.contains("word")
				|| s.contains("excel")
				|| s.contains("spreadsheet")
				|| s.contains("document")
				|| s.endsWith(".pdf")
				|| s.endsWith(".doc")
				|| s.endsWith(".docx")
				|| s.endsWith(".xls")
				|| s.endsWith(".xlsx")
				|| s.endsWith(".txt")
				|| s.endsWith(".rtf");
	}

	private static String extractFileTypeOrName(Object target) {
		if (target == null) {
			return null;
		}

		if (target instanceof JsonNode node) {
			JsonNode fileType = node.get("fileType");
			if (fileType != null && !fileType.isNull() && !fileType.asText("").isBlank()) {
				return fileType.asText();
			}

			JsonNode type = node.get("type");
			if (type != null && !type.isNull() && !type.asText("").isBlank()) {
				return type.asText();
			}

			JsonNode fileName = node.get("fileName");
			if (fileName != null && !fileName.isNull() && !fileName.asText("").isBlank()) {
				return fileName.asText();
			}

			JsonNode name = node.get("name");
			if (name != null && !name.isNull() && !name.asText("").isBlank()) {
				return name.asText();
			}

			return null;
		}

		return toStr(target);
	}

	private static boolean messageFromEquals(JsonNode root, Object target, String expected) {
		String value = null;

		if (target != null) {
			value = toStr(target);
		}

		if (value == null || value.isBlank()) {
			JsonNode node = readByPathStatic(root, "client.messageFrom");
			if (node != null && !node.isNull()) {
				value = node.asText();
			}
		}

		if (value == null || value.isBlank()) {
			JsonNode node = readByPathStatic(root, "message.messageFrom");
			if (node != null && !node.isNull()) {
				value = node.asText();
			}
		}

		return value != null && value.equalsIgnoreCase(expected);
	}

	private static boolean hasTag(Object target, String tagName) {
		if (target == null || tagName == null || tagName.isBlank()) {
			return false;
		}
		String expected = tagName.trim().toLowerCase(Locale.ROOT);
		for (Object item : itemsOf(target)) {
			Object nestedTags = readObjectPath(item, "tags");
			if (nestedTags != null && hasTag(nestedTags, tagName)) {
				return true;
			}
			if (tagMatches(item, expected)) {
				return true;
			}
		}
		return false;
	}

	private static boolean tagMatches(Object tag, String expected) {
		if (tag == null || expected == null) {
			return false;
		}

		if (tag instanceof JsonNode node) {
			JsonNode nameNode = node.get("name");
			if (nameNode != null && !nameNode.isNull()) {
				return nameNode.asText("").trim().toLowerCase(Locale.ROOT).equals(expected);
			}

			JsonNode titleNode = node.get("title");
			if (titleNode != null && !titleNode.isNull()) {
				return titleNode.asText("").trim().toLowerCase(Locale.ROOT).equals(expected);
			}

			return false;
		}

		return String.valueOf(tag).trim().toLowerCase(Locale.ROOT).equals(expected);
	}

	private static boolean noOpenTasks(JsonNode root) {
		JsonNode openTasks = readByPathStatic(root, "client.openTasks");

		if (openTasks == null || openTasks.isNull()) {
			return true;
		}

		if (openTasks.isArray()) {
			return openTasks.isEmpty();
		}

		return false;
	}

	private static JsonNode readByPathStatic(JsonNode root, String path) {
		if (root == null || root.isNull() || path == null || path.isBlank()) {
			return null;
		}

		JsonNode cur = root;
		for (String p : path.split("\\.")) {
			if (cur == null || cur.isNull()) {
				return null;
			}
			cur = cur.get(p);
		}

		return cur;
	}


	private static ZonedDateTime toZonedDateTime(Object v) {
		switch (v) {
			case ZonedDateTime zdt -> {
				return zdt;
			}
			case JsonNode node -> {
				if (node.isTextual()) {
					return toZonedDateTime(node.asText());
				}
				if (node.isNumber()) {
					try {
						return ZonedDateTime.ofInstant(
								Instant.ofEpochMilli(node.asLong()),
								automationZone()
						);
					} catch (Exception ignored) {
						return null;
					}
				}
				return null;
			}

			case String s -> {
				try {
					return ZonedDateTime.parse(s);
				} catch (Exception ignored) {
				}
				try {
					return OffsetDateTime.parse(s).toZonedDateTime();
				} catch (Exception ignored) {
				}
				try {
					return LocalDateTime.parse(s).atZone(automationZone());
				} catch (Exception ignored) {
				}
				return null;
			}

			case Number n -> {
				try {
					return ZonedDateTime.ofInstant(
							Instant.ofEpochMilli(n.longValue()),
							automationZone()
					);
				} catch (Exception ignored) {
					return null;
				}
			}

			case null, default -> {
				return null;
			}
		}
	}


	private static boolean anyOf(Object target, List<Object> args) {
		Set<Object> a = new HashSet<>();
		for (Object x : args) a.add(norm(x));

		if (target instanceof Collection<?> col) {
			for (Object x : col) {
				if (a.contains(norm(x))) {
					return true;
				}
			}
			return false;
		}

		return a.contains(norm(target));
	}

	private static boolean allOf(Object target, List<Object> args) {
		Set<Object> a = new HashSet<>();
		for (Object x : args) a.add(norm(x));

		if (target instanceof Collection<?> col) {
			Set<Object> t = new HashSet<>();
			for (Object x : col) t.add(norm(x));
			return t.containsAll(a);
		}

		return args.size() == 1 && Objects.equals(norm(target), norm(args.getFirst()));
	}

	private static boolean isEmpty(Object target) {
		return switch (target) {
			case null -> true;
			case String s -> s.isBlank();
			case Collection<?> c -> c.isEmpty();
			case Map<?, ?> m -> m.isEmpty();
			default -> false;
		};
	}

	private static String toStr(Object v) {
		return v == null ? null : String.valueOf(v);
	}
}

