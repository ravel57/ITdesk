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
import java.util.*;
import java.util.regex.Pattern;


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

	// ------------------------- PUBLIC API -------------------------

	/**
	 * Вернёт true/false по выражению (как в UI поле "Выражение")
	 */
	public boolean evaluateExpression(String expression, JsonNode payloadRoot) {
		if (expression == null || expression.isBlank()) {
			return true;
		}
		Lexer lx = new Lexer(expression);
		Parser parser = new Parser(lx);
		ExprNode ast = parser.parseExpression();
		return asBool(ast.eval(payloadRoot));
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
		StringBuilder cur = new StringBuilder();
		boolean inString = false;
		char stringQuote = 0;
		for (int i = 0; i < inside.length(); i++) {
			char c = inside.charAt(i);
			if (inString) {
				cur.append(c);
				if (c == stringQuote && inside.charAt(i - 1) != '\\') {
					inString = false;
				}
				continue;
			}
			if (c == '\'' || c == '"') {
				inString = true;
				stringQuote = c;
				cur.append(c);
				continue;
			}
			if (c == ',') {
				out.add(parseLiteral(cur.toString().trim()));
				cur.setLength(0);
				continue;
			}
			cur.append(c);
		}
		if (!cur.toString().trim().isBlank()) out.add(parseArg(cur.toString().trim(), payloadRoot));
		return out;
	}

	private Object parseArg(String raw, JsonNode payloadRoot) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		raw = raw.trim();
		// 'строка' или "строка"
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
		if (raw.matches("-?\\d+")) {
			try {
				return Long.parseLong(raw);
			} catch (Exception ignored) {
			}
		}
		// ссылка на payload: message.text / client.id / client.messagesCount
		if (raw.matches("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) {
			JsonNode node = readByPath(payloadRoot, raw);
			if (node == null || node.isNull()) {
				return null;
			}
			if (node.isTextual()) {
				return node.asText();
			}
			if (node.isNumber()) {
				return node.decimalValue();
			}
			if (node.isBoolean()) {
				return node.asBoolean();
			}
			return node; // объект/массив
		}
		// иначе — как строка
		return raw;
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
			if (startsWith("&&") || startsWith("||") || startsWith(">=") || startsWith("<=")) {
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

		private ExprNode parseOr() {
			ExprNode left = parseAnd();
			while (cur.type == TokType.OP && "||".equals(cur.text)) {
				consume();
				ExprNode right = parseAnd();
				ExprNode finalLeft = left;
				left = ctx -> asBool(finalLeft.eval(ctx)) || asBool(right.eval(ctx));
			}
			return left;
		}

		private ExprNode parseAnd() {
			ExprNode left = parseNot();
			while (cur.type == TokType.OP && "&&".equals(cur.text)) {
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

			// ident: function(...) OR path (client.messages.size())
			if (cur.type == TokType.IDENT) {
				String name = cur.text;
				consume();

				// function call: starts_with(x,y)
				if (cur.type == TokType.LPAREN) {
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

					TriggerFunctionsType fn = mapFn(name);
					return ctx -> {
						Object target = args.isEmpty() ? null : args.getFirst().eval(ctx);
						List<Object> rest = new ArrayList<>();
						for (int i = 1; i < args.size(); i++) rest.add(args.get(i).eval(ctx));
						return evalFunction(fn, target, rest);
					};
				}

				// path: name(.name | .size())*
				List<PathSeg> segs = new ArrayList<>();
				segs.add(new PathSeg(name, false));

				while (cur.type == TokType.DOT) {
					consume();
					expect(TokType.IDENT);
					String part = cur.text;
					consume();

					// .size()
					if ("size".equalsIgnoreCase(part) && cur.type == TokType.LPAREN) {
						consume();
						expect(TokType.RPAREN);
						consume();
						segs.add(new PathSeg("size", true));
						continue;
					}

					segs.add(new PathSeg(part, false));
				}

				return ctx -> resolvePath(ctx, segs);
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

	private static Object sizeOf(JsonNode node) {
		if (node == null || node.isNull()) {
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
			String v = s.substring(1, s.length() - 1);
			return v.replace("\\'", "'").replace("\\\"", "\"");
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

	private static boolean evalFunction(TriggerFunctionsType fn, Object target, List<Object> args) {
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
			case ANY_OF -> anyOf(target, args);
			case NONE_OF -> !anyOf(target, args);
			case ALL_OF -> allOf(target, args);

			case IS_NULL -> target == null;
			case NOT_NULL -> target != null;

			case IS_EMPTY -> isEmpty(target);
			case NOT_EMPTY -> !isEmpty(target);

			case IS_TRUE -> asBool(target);
			case IS_FALSE -> !asBool(target);
		};
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

