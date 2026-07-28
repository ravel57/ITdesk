package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.AutomationExpressionSuggestionRequest;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerFunctionsType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Service
public class AutomationExpressionMetadataService {

	private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_-]{0,127}");
	private static final Pattern ZERO_ARGUMENT_METHOD = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\(\\)");
	private static final Set<String> HIDDEN_PROPERTIES = Set.of(
			"password",
			"authorities",
			"accountNonExpired",
			"accountNonLocked",
			"credentialsNonExpired"
	);
	private static final Map<String, String> JAVA_METHOD_ALIASES = Map.of(
			"toLowerCase", "lower",
			"toUpperCase", "upper"
	);
	private static final Set<String> STRING_METHODS = Set.of(
			"startsWith",
			"endsWith",
			"contains",
			"lower",
			"upper",
			"trim",
			"length",
			"isEmpty",
			"equalsIgnoreCase",
			"matches"
	);
	private static final Set<String> COLLECTION_METHODS = Set.of("size", "isEmpty");
	private static final Set<String> MAP_METHODS = Set.of("size", "isEmpty");
	private static final Set<String> TEMPORAL_METHODS = Set.of("isBefore", "isAfter");

	private final ObjectMapper objectMapper;
	private final Map<String, RootDefinition> roots;
	private final Map<String, RootDefinition> actionRoots;
	private final Set<String> runtimeFunctionNames;
	private final Map<String, List<PropertyDefinition>> propertyCache = new ConcurrentHashMap<>();
	private final Map<String, List<MethodDefinition>> methodCache = new ConcurrentHashMap<>();
	private final Map<String, List<MethodDefinition>> actionMethodCache = new ConcurrentHashMap<>();


	public AutomationExpressionMetadataService(@Qualifier("legacyObjectMapper") ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		this.runtimeFunctionNames = Stream.of(TriggerFunctionsType.class.getEnumConstants())
				.flatMap(function -> Stream.of(function.name(), function.getOperator()))
				.filter(Objects::nonNull)
				.map(value -> value.toLowerCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet());

		var typeFactory = objectMapper.getTypeFactory();
		Map<String, RootDefinition> definitions = new LinkedHashMap<>();
		register(definitions, "task", "Заявка", typeFactory.constructType(Task.class));
		register(definitions, "client", "Клиент", typeFactory.constructType(Client.class));
		register(definitions, "message", "Сообщение", typeFactory.constructType(Message.class));
		register(definitions, "user", "Пользователь", typeFactory.constructType(User.class));
		register(definitions, "mentionedUser", "Упомянутый пользователь", typeFactory.constructType(User.class));
		register(definitions, "organization", "Организация", typeFactory.constructType(Organization.class));
		register(definitions, "knowledge", "Статья базы знаний", typeFactory.constructType(Knowledge.class));
		register(definitions, "tag", "Тег события", typeFactory.constructType(Tag.class));
		register(definitions, "oldStatus", "Предыдущий статус", typeFactory.constructType(Status.class));
		register(definitions, "newStatus", "Новый статус", typeFactory.constructType(Status.class));
		register(definitions, "oldPriority", "Предыдущий приоритет", typeFactory.constructType(Priority.class));
		register(definitions, "newPriority", "Новый приоритет", typeFactory.constructType(Priority.class));
		register(definitions, "oldExecutor", "Предыдущий исполнитель", typeFactory.constructType(User.class));
		register(definitions, "newExecutor", "Новый исполнитель", typeFactory.constructType(User.class));
		register(definitions, "oldSupportLine", "Предыдущая линия", typeFactory.constructType(SupportLine.class));
		register(definitions, "newSupportLine", "Новая линия", typeFactory.constructType(SupportLine.class));
		register(definitions, "changes", "Изменения события", typeFactory.constructMapType(Map.class, String.class, Object.class));
		register(definitions, "reason", "Причина изменения", typeFactory.constructType(String.class));
		register(definitions, "var", "Переменные автоматизации", null);
		this.roots = Collections.unmodifiableMap(definitions);

		Map<String, RootDefinition> actionDefinitions = new LinkedHashMap<>();
		register(actionDefinitions, "client", "Действия с клиентом", typeFactory.constructType(AutomationActionExecutor.ClientApi.class));
		register(actionDefinitions, "task", "Действия с заявкой", typeFactory.constructType(AutomationActionExecutor.TaskApi.class));
		register(actionDefinitions, "notify", "Уведомления", typeFactory.constructType(AutomationActionExecutor.NotifyApi.class));
		register(actionDefinitions, "webhook", "Внешние вызовы", typeFactory.constructType(AutomationActionExecutor.WebhookApi.class));
		this.actionRoots = Collections.unmodifiableMap(actionDefinitions);
	}


	public Map<String, Object> roots() {
		List<Map<String, Object>> items = roots.values().stream()
				.map(root -> suggestion(
						root.name(),
						root.name(),
						root.name(),
						root.label(),
						root.type() == null ? "VARIABLES" : kind(root.type()),
						root.type() == null ? "runtime" : displayType(root.type()),
						rootHasChildren(root),
						false,
						rootHasChildren(root),
						0,
						List.of()
				))
				.toList();
		return Map.of("suggestions", items);
	}


	public Map<String, Object> suggest(AutomationExpressionSuggestionRequest request) {
		String text = request == null || request.getText() == null ? "" : request.getText();
		int cursor = request == null || request.getCursor() == null
				? text.length()
				: Math.max(0, Math.min(request.getCursor(), text.length()));
		int limit = request == null || request.getLimit() == null
				? 50
				: Math.max(1, Math.min(request.getLimit(), 100));
		String requestedMode = request == null || request.getMode() == null
				? "EXPRESSION"
				: request.getMode().trim().toUpperCase(Locale.ROOT);
		boolean actionMode = "ACTION".equals(requestedMode);
		List<String> variables = request == null ? List.of() : safeVariables(request.getVariables());

		TokenContext context = tokenContext(text, cursor);
		ActionCursorContext actionContext = actionMode
				? actionCursorContext(text, cursor)
				: new ActionCursorContext(false, false);

		List<Map<String, Object>> suggestions;
		String effectiveMode;
		if (actionMode && actionContext.insideString()) {
			suggestions = List.of();
			effectiveMode = "STRING";
		} else if (actionMode && !actionContext.insideArguments()) {
			suggestions = resolveActionSuggestions(
					context.parentPath(),
					context.query(),
					variables,
					limit
			);
			effectiveMode = "ACTION";
		} else {
			suggestions = resolveSuggestions(
					context.parentPath(),
					context.query(),
					variables,
					limit
			);
			effectiveMode = "EXPRESSION";
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("replaceFrom", context.replaceFrom());
		result.put("replaceTo", context.replaceTo());
		result.put("token", context.token());
		result.put("parentPath", context.parentPath());
		result.put("query", context.query());
		result.put("mode", effectiveMode);
		result.put("suggestions", suggestions);
		return result;
	}


	private List<Map<String, Object>> resolveActionSuggestions(
			String parentPath,
			String query,
			List<String> variables,
			int limit
	) {
		String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
		if (parentPath == null || parentPath.isBlank()) {
			LinkedHashSet<String> rootNames = new LinkedHashSet<>();
			rootNames.addAll(actionRoots.keySet());
			rootNames.addAll(roots.keySet());

			return rootNames.stream()
					.map(name -> {
						RootDefinition actionRoot = actionRoots.get(name);
						RootDefinition dataRoot = roots.get(name);
						String description;
						if (actionRoot != null && dataRoot != null) {
							description = dataRoot.label() + " — свойства и действия";
						} else if (actionRoot != null) {
							description = actionRoot.label();
						} else {
							description = dataRoot.label();
						}
						return new Object[]{name, actionRoot, dataRoot, description};
					})
					.filter(item -> matches((String) item[0], (String) item[3], normalizedQuery))
					.limit(limit)
					.map(item -> {
						String name = (String) item[0];
						RootDefinition actionRoot = (RootDefinition) item[1];
						RootDefinition dataRoot = (RootDefinition) item[2];
						String description = (String) item[3];
						JavaType displayJavaType = dataRoot != null && dataRoot.type() != null
								? dataRoot.type()
								: actionRoot == null ? null : actionRoot.type();
						boolean hasChildren = actionRoot != null
								|| dataRoot != null && rootHasChildren(dataRoot);
						String suggestionKind = actionRoot != null
								? "ACTION_API"
								: dataRoot != null && dataRoot.type() == null ? "VARIABLES" : kind(displayJavaType);
						String suggestionType = displayJavaType == null ? "runtime" : displayType(displayJavaType);
						return suggestion(
								name,
								name,
								name,
								description,
								suggestionKind,
								suggestionType,
								hasChildren,
								false,
								hasChildren,
								0,
								List.of()
						);
					})
					.toList();
		}

		String[] segments = parentPath.split("\\.");
		if (segments.length == 0) return List.of();

		RootDefinition actionRoot = actionRoots.get(segments[0]);
		RootDefinition dataRoot = roots.get(segments[0]);
		if (actionRoot == null && dataRoot == null) return List.of();

		if (dataRoot != null && "var".equals(dataRoot.name())) {
			if (segments.length > 1) return List.of();
			return variables.stream()
					.filter(variable -> matches(variable, variable, normalizedQuery))
					.limit(limit)
					.map(variable -> suggestion(
							variable,
							"var." + variable,
							variable,
							"Переменная автоматизации",
							"VARIABLE",
							"runtime",
							false,
							false,
							false,
							0,
							List.of()
					))
					.toList();
		}

		JavaType actionType = actionRoot == null ? null : actionRoot.type();
		JavaType dataType = dataRoot == null ? null : dataRoot.type();
		for (int index = 1; index < segments.length; index++) {
			actionType = resolveActionSegment(actionType, segments[index]);
			dataType = resolveSegment(dataType, segments[index]);
			if (actionType == null && dataType == null) return List.of();
		}

		List<MemberDefinition> members = new ArrayList<>();
		members.addAll(propertyMembers(dataType));
		members.addAll(actionMethodMembers(actionType));
		members.addAll(methodMembers(dataType));

		Map<String, MemberDefinition> unique = new LinkedHashMap<>();
		for (MemberDefinition member : members) {
			String key = member.kind() + "|" + member.label();
			unique.putIfAbsent(key, member);
		}

		return unique.values().stream()
				.filter(member -> matches(member.queryName(), member.description() + " " + member.label(), normalizedQuery))
				.limit(limit)
				.map(member -> suggestion(
						member.insertText(),
						parentPath + "." + member.insertText(),
						member.label(),
						member.description(),
						member.kind(),
						member.typeName(),
						member.hasChildren(),
						member.callable(),
						member.appendDot(),
						member.caretOffset(),
						member.parameters()
				))
				.toList();
	}


	private List<Map<String, Object>> resolveSuggestions(
			String parentPath,
			String query,
			List<String> variables,
			int limit
	) {
		String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
		if (parentPath == null || parentPath.isBlank()) {
			return roots.values().stream()
					.filter(root -> matches(root.name(), root.label(), normalizedQuery))
					.limit(limit)
					.map(root -> suggestion(
							root.name(),
							root.name(),
							root.name(),
							root.label(),
							root.type() == null ? "VARIABLES" : kind(root.type()),
							root.type() == null ? "runtime" : displayType(root.type()),
							rootHasChildren(root),
							false,
							rootHasChildren(root),
							0,
							List.of()
					))
					.toList();
		}

		String[] segments = parentPath.split("\\.");
		if (segments.length == 0) return List.of();

		RootDefinition root = roots.get(segments[0]);
		if (root == null) return List.of();

		if ("var".equals(root.name())) {
			if (segments.length > 1) return List.of();
			return variables.stream()
					.filter(variable -> matches(variable, variable, normalizedQuery))
					.limit(limit)
					.map(variable -> suggestion(
							variable,
							"var." + variable,
							variable,
							"Переменная автоматизации",
							"VARIABLE",
							"runtime",
							false,
							false,
							false,
							0,
							List.of()
					))
					.toList();
		}

		JavaType currentType = root.type();
		for (int i = 1; i < segments.length; i++) {
			currentType = resolveSegment(currentType, segments[i]);
			if (currentType == null) return List.of();
		}

		List<MemberDefinition> members = new ArrayList<>();
		members.addAll(propertyMembers(currentType));
		members.addAll(methodMembers(currentType));

		return members.stream()
				.filter(member -> matches(member.queryName(), member.description() + " " + member.label(), normalizedQuery))
				.limit(limit)
				.map(member -> suggestion(
						member.insertText(),
						parentPath + "." + member.insertText(),
						member.label(),
						member.description(),
						member.kind(),
						member.typeName(),
						member.hasChildren(),
						member.callable(),
						member.appendDot(),
						member.caretOffset(),
						member.parameters()
				))
				.toList();
	}


	private JavaType resolveSegment(JavaType sourceType, String segment) {
		if (sourceType == null || segment == null || segment.isBlank()) return null;

		if (sourceType.isCollectionLikeType() || sourceType.isArrayType()) {
			if ("last()".equalsIgnoreCase(segment)) {
				return sourceType.getContentType();
			}
		}

		Matcher methodMatcher = ZERO_ARGUMENT_METHOD.matcher(segment);
		if (methodMatcher.matches()) {
			String methodName = methodMatcher.group(1);
			return methodDefinitions(sourceType).stream()
					.filter(method -> method.parameterTypes().isEmpty())
					.filter(method -> method.expressionName().equalsIgnoreCase(methodName))
					.map(MethodDefinition::returnType)
					.findFirst()
					.orElse(null);
		}

		return properties(sourceType).stream()
				.filter(property -> property.name().equals(segment))
				.map(PropertyDefinition::javaType)
				.findFirst()
				.orElse(null);
	}


	private List<MemberDefinition> propertyMembers(JavaType type) {
		return properties(type).stream()
				.map(property -> new MemberDefinition(
						property.name(),
						property.name(),
						property.name(),
						property.description(),
						property.kind(),
						property.typeName(),
						property.hasChildren(),
						false,
						property.hasChildren(),
						0,
						List.of()
				))
				.toList();
	}


	private List<MemberDefinition> methodMembers(JavaType type) {
		List<MemberDefinition> result = new ArrayList<>();

		if (type != null && type.isArrayType()) {
			result.add(new MemberDefinition(
					"size()",
					"size",
					"size()",
					"Количество элементов массива",
					"METHOD",
					"Integer",
					false,
					true,
					false,
					0,
					List.of()
			));
		}

		if (type != null && (type.isCollectionLikeType() || type.isArrayType())) {
			JavaType contentType = type.getContentType();
			result.add(new MemberDefinition(
					"last()",
					"last",
					"last()",
					"Последний элемент коллекции",
					"METHOD",
					displayType(contentType),
					isPotentiallyNavigable(contentType),
					true,
					isPotentiallyNavigable(contentType),
					0,
					List.of()
			));
		}

		result.addAll(virtualMethodMembers(type));

		for (MethodDefinition method : methodDefinitions(type)) {
			String parametersText = String.join(", ", method.parameterTypes());
			String signature = method.expressionName() + "(" + parametersText + ")";
			boolean hasParameters = !method.parameterTypes().isEmpty();
			boolean hasChildren = isPotentiallyNavigable(method.returnType());
			List<Map<String, Object>> parameters = new ArrayList<>();
			for (int index = 0; index < method.parameterTypes().size(); index++) {
				parameters.add(Map.of(
						"index", index,
						"type", method.parameterTypes().get(index)
				));
			}

			result.add(new MemberDefinition(
					method.expressionName() + "()",
					method.expressionName(),
					signature,
					"Метод " + method.declaringType() + "." + method.javaSignature(),
					"METHOD",
					displayType(method.returnType()),
					hasChildren,
					true,
					!hasParameters && hasChildren,
					hasParameters ? -1 : 0,
					List.copyOf(parameters)
			));
		}

		Map<String, MemberDefinition> unique = new LinkedHashMap<>();
		for (MemberDefinition member : result) {
			unique.putIfAbsent(member.label(), member);
		}
		return List.copyOf(unique.values());
	}


	private List<MemberDefinition> virtualMethodMembers(JavaType type) {
		if (type == null || type.getRawClass() == null) {
			return List.of();
		}

		List<MemberDefinition> result = new ArrayList<>();
		Class<?> rawType = type.getRawClass();
		JavaType contentType = type.isCollectionLikeType() || type.isArrayType()
				? type.getContentType()
				: null;
		Class<?> contentClass = contentType == null ? null : contentType.getRawClass();
		boolean collection = type.isCollectionLikeType() || type.isArrayType();
		boolean objectLike = !isSimple(type) && !type.isMapLikeType();

		if (collection || objectLike) {
			result.add(virtualMethod(
					"fieldContains",
					"Ищет текст в указанном поле. Поддерживает вложенный путь, например status.name",
					"Boolean",
					"String field",
					"String text"
			));
			result.add(virtualMethod(
					"fieldEquals",
					"Проверяет точное значение указанного поля хотя бы у одного объекта",
					"Boolean",
					"String field",
					"Object value"
			));
			result.add(virtualMethod(
					"fieldExists",
					"Проверяет, что поле существует и не пустое",
					"Boolean",
					"String field"
			));
		}

		if (rawType == Client.class) {
			result.add(virtualMethod("hasOpenTasks", "У клиента есть открытые заявки", "Boolean"));
			result.add(virtualMethod("noOpenTasks", "У клиента нет открытых заявок", "Boolean"));
			result.add(virtualMethod("openTasksCount", "Количество открытых заявок клиента", "Long"));
			result.add(virtualMethod("messagesCount", "Количество сообщений клиента", "Long"));
			result.add(virtualMethod("incomeMessagesCount", "Количество входящих сообщений клиента", "Long"));
			result.add(virtualMethod("outcomeMessagesCount", "Количество исходящих сообщений клиента", "Long"));
		}

		if (rawType == Task.class) {
			addTaskObjectMethods(result);
		}

		if (rawType == Message.class) {
			result.add(virtualMethod("textContains", "Текст сообщения содержит фрагмент без учёта регистра", "Boolean", "String text"));
			result.add(virtualMethod("hasAttachment", "Сообщение содержит вложение", "Boolean"));
			result.add(virtualMethod("isImage", "Вложение является изображением", "Boolean"));
			result.add(virtualMethod("isDocument", "Вложение является документом", "Boolean"));
		}

		if (collection && contentClass == Task.class) {
			result.add(virtualMethod("nameContains", "Название хотя бы одной заявки содержит текст", "Boolean", "String text"));
			result.add(virtualMethod("descriptionContains", "Описание хотя бы одной заявки содержит текст", "Boolean", "String text"));
			result.add(virtualMethod("statusIs", "Хотя бы одна заявка имеет указанный статус", "Boolean", "String status"));
			result.add(virtualMethod("priorityIs", "Хотя бы одна заявка имеет указанный приоритет", "Boolean", "String priority"));
			result.add(virtualMethod("typeIs", "Хотя бы одна заявка имеет указанный тип", "Boolean", "String type"));
			result.add(virtualMethod("supportLineIs", "Хотя бы одна заявка относится к линии по имени или ID", "Boolean", "Object line"));
			result.add(virtualMethod("assignedTo", "Хотя бы одна заявка назначена пользователю по имени, username или ID", "Boolean", "Object user"));
			result.add(virtualMethod("hasTag", "Хотя бы одна заявка содержит указанный тег", "Boolean", "String tag"));
			result.add(virtualMethod("hasOpen", "В коллекции есть открытая заявка", "Boolean"));
			result.add(virtualMethod("hasClosed", "В коллекции есть закрытая заявка", "Boolean"));
			result.add(virtualMethod("openCount", "Количество открытых заявок", "Long"));
			result.add(virtualMethod("closedCount", "Количество закрытых заявок", "Long"));
			result.add(virtualMethod("overdueCount", "Количество открытых заявок с просроченным дедлайном", "Long"));
			result.add(virtualMethod("unassignedCount", "Количество заявок без исполнителя", "Long"));
		}

		if (collection && contentClass == Message.class) {
			result.add(virtualMethod("textContains", "Текст хотя бы одного сообщения содержит фрагмент", "Boolean", "String text"));
			result.add(virtualMethod("incomingTextContains", "Текст входящего сообщения содержит фрагмент", "Boolean", "String text"));
			result.add(virtualMethod("outgoingTextContains", "Текст исходящего сообщения содержит фрагмент", "Boolean", "String text"));
			result.add(virtualMethod("commentContains", "Текст внутреннего комментария содержит фрагмент", "Boolean", "String text"));
			result.add(virtualMethod("incomingCount", "Количество входящих сообщений", "Long"));
			result.add(virtualMethod("outgoingCount", "Количество исходящих сообщений", "Long"));
			result.add(virtualMethod("unreadCount", "Количество непрочитанных сообщений", "Long"));
			result.add(virtualMethod("attachmentCount", "Количество сообщений с вложениями", "Long"));
			result.add(virtualMethod("hasAttachment", "В коллекции есть сообщение с вложением", "Boolean"));
		}

		if (collection && contentClass == ChecklistItem.class) {
			result.add(virtualMethod("textContains", "Текст пункта чек-листа содержит фрагмент", "Boolean", "String text"));
			result.add(virtualMethod("hasIncomplete", "В чек-листе есть невыполненный пункт", "Boolean"));
			result.add(virtualMethod("completedCount", "Количество выполненных пунктов", "Long"));
			result.add(virtualMethod("incompleteCount", "Количество невыполненных пунктов", "Long"));
		}

		if (collection && contentClass == Tag.class) {
			result.add(virtualMethod("nameContains", "Название хотя бы одного тега содержит текст", "Boolean", "String text"));
		}

		return result;
	}


	private void addTaskObjectMethods(List<MemberDefinition> target) {
		target.add(virtualMethod("nameContains", "Название заявки содержит текст", "Boolean", "String text"));
		target.add(virtualMethod("descriptionContains", "Описание заявки содержит текст", "Boolean", "String text"));
		target.add(virtualMethod("statusIs", "Статус заявки совпадает с указанным", "Boolean", "String status"));
		target.add(virtualMethod("priorityIs", "Приоритет заявки совпадает с указанным", "Boolean", "String priority"));
		target.add(virtualMethod("typeIs", "Тип заявки совпадает с указанным", "Boolean", "String type"));
		target.add(virtualMethod("supportLineIs", "Линия заявки совпадает по имени или ID", "Boolean", "Object line"));
		target.add(virtualMethod("assignedTo", "Заявка назначена пользователю по имени, username или ID", "Boolean", "Object user"));
		target.add(virtualMethod("hasTag", "Заявка содержит указанный тег", "Boolean", "String tag"));
		target.add(virtualMethod("hasAssignee", "У заявки назначен исполнитель", "Boolean"));
		target.add(virtualMethod("hasDeadline", "У заявки установлен дедлайн", "Boolean"));
		target.add(virtualMethod("isOverdue", "Дедлайн открытой заявки просрочен", "Boolean"));
		target.add(virtualMethod("isCompleted", "Заявка закрыта", "Boolean"));
	}


	private MemberDefinition virtualMethod(
			String name,
			String description,
			String returnType,
			String... parameterTypes
	) {
		List<String> params = parameterTypes == null ? List.of() : List.of(parameterTypes);
		String signature = name + "(" + String.join(", ", params) + ")";
		List<Map<String, Object>> parameters = new ArrayList<>();
		for (int index = 0; index < params.size(); index++) {
			parameters.add(Map.of("index", index, "type", params.get(index)));
		}
		return new MemberDefinition(
				name + "()",
				name,
				signature,
				description,
				"METHOD",
				returnType,
				false,
				true,
				false,
				params.isEmpty() ? 0 : -1,
				List.copyOf(parameters)
		);
	}



	private JavaType resolveActionSegment(JavaType sourceType, String segment) {
		if (sourceType == null || segment == null || segment.isBlank()) return null;
		Matcher matcher = ZERO_ARGUMENT_METHOD.matcher(segment);
		if (!matcher.matches()) return null;
		String methodName = matcher.group(1);
		return actionMethodDefinitions(sourceType).stream()
				.filter(method -> method.parameterTypes().isEmpty())
				.filter(method -> method.expressionName().equalsIgnoreCase(methodName))
				.map(MethodDefinition::returnType)
				.findFirst()
				.orElse(null);
	}


	private List<MemberDefinition> actionMethodMembers(JavaType type) {
		return actionMethodDefinitions(type).stream()
				.map(method -> {
					String parametersText = String.join(", ", method.parameterTypes());
					String signature = method.expressionName() + "(" + parametersText + ")";
					boolean hasParameters = !method.parameterTypes().isEmpty();
					List<Map<String, Object>> parameters = new ArrayList<>();
					for (int index = 0; index < method.parameterTypes().size(); index++) {
						parameters.add(Map.of("index", index, "type", method.parameterTypes().get(index)));
					}
					return new MemberDefinition(
							method.expressionName() + "()",
							method.expressionName(),
							signature,
							"Метод автоматизации " + method.declaringType() + "." + method.javaSignature(),
							"ACTION_METHOD",
							displayType(method.returnType()),
							false,
							true,
							false,
							hasParameters ? -1 : 0,
							List.copyOf(parameters)
					);
				})
				.toList();
	}


	private List<MethodDefinition> actionMethodDefinitions(JavaType type) {
		if (type == null || type.getRawClass() == null) return List.of();
		String cacheKey = type.toCanonical();
		return actionMethodCache.computeIfAbsent(cacheKey, ignored -> inspectActionMethods(type));
	}


	private List<MethodDefinition> inspectActionMethods(JavaType ownerType) {
		Class<?> rawType = ownerType.getRawClass();
		if (rawType == null) return List.of();

		Map<String, MethodDefinition> unique = new LinkedHashMap<>();
		for (Method method : rawType.getMethods()) {
			if (method == null
					|| !Modifier.isPublic(method.getModifiers())
					|| Modifier.isStatic(method.getModifiers())
					|| method.isBridge()
					|| method.isSynthetic()
					|| method.getDeclaringClass() == Object.class
					|| method.getDeclaringClass() != rawType) {
				continue;
			}

			JavaType returnType = objectMapper.getTypeFactory().constructType(method.getGenericReturnType());
			List<String> parameterTypes = new ArrayList<>();
			for (java.lang.reflect.Type parameterType : method.getGenericParameterTypes()) {
				parameterTypes.add(displayType(objectMapper.getTypeFactory().constructType(parameterType)));
			}
			String key = method.getName().toLowerCase(Locale.ROOT) + "(" + String.join(",", parameterTypes) + ")";
			String javaSignature = method.getName() + "(" + String.join(", ", parameterTypes) + ")";
			unique.putIfAbsent(key, new MethodDefinition(
					method.getName(),
					javaSignature,
					method.getDeclaringClass().getSimpleName(),
					parameterTypes,
					returnType
			));
		}

		return unique.values().stream()
				.sorted(Comparator
						.comparing(MethodDefinition::expressionName, String.CASE_INSENSITIVE_ORDER)
						.thenComparing(method -> method.parameterTypes().size())
						.thenComparing(MethodDefinition::javaSignature, String.CASE_INSENSITIVE_ORDER))
				.toList();
	}

	private List<PropertyDefinition> properties(JavaType type) {
		if (type == null || isSimple(type) || type.isCollectionLikeType() || type.isArrayType() || type.isMapLikeType()) {
			return List.of();
		}
		String cacheKey = type.toCanonical();
		return propertyCache.computeIfAbsent(cacheKey, ignored -> inspectProperties(type));
	}


	private List<PropertyDefinition> inspectProperties(JavaType type) {
		BeanDescription description = objectMapper.getSerializationConfig().introspect(type);
		List<PropertyDefinition> result = new ArrayList<>();

		for (BeanPropertyDefinition property : description.findProperties()) {
			if (!property.couldSerialize()) continue;
			String name = property.getName();
			if (name == null || name.isBlank() || name.startsWith("_") || HIDDEN_PROPERTIES.contains(name)) continue;

			AnnotatedMember member = property.getAccessor();
			if (member == null) member = property.getPrimaryMember();
			if (member == null) continue;

			JavaType propertyType = member.getType();
			if (propertyType == null) continue;
			addProperty(result, name, humanize(name), propertyType);
		}

		if (Client.class.isAssignableFrom(type.getRawClass())) {
			JavaType messages = objectMapper.getTypeFactory().constructCollectionType(List.class, Message.class);
			JavaType tasks = objectMapper.getTypeFactory().constructCollectionType(List.class, Task.class);
			addProperty(result, "messages", "Все сообщения клиента", messages);
			addProperty(result, "incomeMessages", "Входящие сообщения клиента", messages);
			addProperty(result, "outcomeMessages", "Исходящие сообщения клиента", messages);
			addProperty(result, "tasks", "Все заявки клиента", tasks);
			addProperty(result, "openTasks", "Открытые заявки клиента", tasks);
		}

		result.sort(Comparator
				.comparingInt((PropertyDefinition property) -> propertyOrder(property.name()))
				.thenComparing(PropertyDefinition::name, String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(result);
	}


	private List<MethodDefinition> methodDefinitions(JavaType type) {
		if (type == null || type.getRawClass() == null) return List.of();
		String cacheKey = type.toCanonical();
		return methodCache.computeIfAbsent(cacheKey, ignored -> inspectMethods(type));
	}


	private List<MethodDefinition> inspectMethods(JavaType ownerType) {
		Class<?> rawType = ownerType.getRawClass();
		if (rawType == null) return List.of();

		Map<String, MethodDefinition> unique = new LinkedHashMap<>();
		for (Method method : rawType.getMethods()) {
			if (!isSafeExpressionMethod(ownerType, method)) continue;

			String expressionName = expressionMethodName(method.getName());
			JavaType returnType = objectMapper.getTypeFactory().constructType(method.getGenericReturnType());
			List<String> parameterTypes = new ArrayList<>();
			for (java.lang.reflect.Type parameterType : method.getGenericParameterTypes()) {
				parameterTypes.add(displayType(objectMapper.getTypeFactory().constructType(parameterType)));
			}
			String key = expressionName.toLowerCase(Locale.ROOT) + "(" + String.join(",", parameterTypes) + ")";
			String javaSignature = method.getName() + "(" + String.join(", ", parameterTypes) + ")";

			unique.putIfAbsent(key, new MethodDefinition(
					expressionName,
					javaSignature,
					method.getDeclaringClass().getSimpleName(),
					parameterTypes,
					returnType
			));
		}

		return unique.values().stream()
				.sorted(Comparator
						.comparing(MethodDefinition::expressionName, String.CASE_INSENSITIVE_ORDER)
						.thenComparing(method -> method.parameterTypes().size())
						.thenComparing(MethodDefinition::javaSignature, String.CASE_INSENSITIVE_ORDER))
				.toList();
	}


	private boolean isSafeExpressionMethod(JavaType ownerType, Method method) {
		if (method == null
				|| !Modifier.isPublic(method.getModifiers())
				|| Modifier.isStatic(method.getModifiers())
				|| method.isBridge()
				|| method.isSynthetic()
				|| method.getDeclaringClass() == Object.class
				|| method.getReturnType() == void.class) {
			return false;
		}

		String expressionName = expressionMethodName(method.getName());
		if (!runtimeFunctionNames.contains(expressionName.toLowerCase(Locale.ROOT))
				&& !"size".equalsIgnoreCase(expressionName)) {
			return false;
		}

		int parameterCount = method.getParameterCount();
		if (isString(ownerType)) {
			if (!STRING_METHODS.contains(expressionName)) return false;
			return switch (expressionName) {
				case "startsWith", "endsWith", "contains", "equalsIgnoreCase", "matches" -> parameterCount == 1;
				case "lower", "upper", "trim", "length", "isEmpty" -> parameterCount == 0;
				default -> false;
			};
		}

		if (ownerType.isCollectionLikeType() || ownerType.isArrayType()) {
			return COLLECTION_METHODS.contains(expressionName) && parameterCount == 0;
		}

		if (ownerType.isMapLikeType()) {
			return MAP_METHODS.contains(expressionName) && parameterCount == 0;
		}

		if (ownerType.getRawClass() != null && Temporal.class.isAssignableFrom(ownerType.getRawClass())) {
			return TEMPORAL_METHODS.contains(expressionName) && parameterCount == 1;
		}

		return false;
	}


	private String expressionMethodName(String javaMethodName) {
		return JAVA_METHOD_ALIASES.getOrDefault(javaMethodName, javaMethodName);
	}


	private void addProperty(List<PropertyDefinition> target, String name, String description, JavaType propertyType) {
		if (target.stream().anyMatch(property -> property.name().equals(name))) return;
		target.add(new PropertyDefinition(
				name,
				description,
				kind(propertyType),
				displayType(propertyType),
				isPotentiallyNavigable(propertyType),
				propertyType
		));
	}


	private boolean rootHasChildren(RootDefinition root) {
		return root != null && (root.type() == null || isPotentiallyNavigable(root.type()));
	}


	private Map<String, Object> suggestion(
			String insertText,
			String path,
			String label,
			String description,
			String kind,
			String type,
			boolean hasChildren,
			boolean callable,
			boolean appendDot,
			int caretOffset,
			List<Map<String, Object>> parameters
	) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("insertText", insertText);
		item.put("path", path);
		item.put("label", label);
		item.put("description", description);
		item.put("kind", kind);
		item.put("type", type);
		item.put("hasChildren", hasChildren);
		item.put("callable", callable);
		item.put("appendDot", appendDot);
		item.put("caretOffset", caretOffset);
		item.put("parameters", parameters == null ? List.of() : parameters);
		return item;
	}


	private TokenContext tokenContext(String text, int cursor) {
		int tokenStart = cursor;
		int closedMethodParentheses = 0;
		while (tokenStart > 0) {
			char value = text.charAt(tokenStart - 1);
			if (Character.isLetterOrDigit(value) || value == '_' || value == '-' || value == '.') {
				tokenStart--;
				continue;
			}
			if (value == ')') {
				closedMethodParentheses++;
				tokenStart--;
				continue;
			}
			if (value == '(' && closedMethodParentheses > 0) {
				closedMethodParentheses--;
				tokenStart--;
				continue;
			}
			break;
		}

		String token = text.substring(tokenStart, cursor);
		int lastDot = token.lastIndexOf('.');
		String parentPath = lastDot < 0 ? "" : token.substring(0, lastDot);
		String query = lastDot < 0 ? token : token.substring(lastDot + 1);
		int replaceFrom = lastDot < 0 ? tokenStart : tokenStart + lastDot + 1;
		int replaceTo = cursor;
		while (replaceTo < text.length() && isIdentifierCharacter(text.charAt(replaceTo))) replaceTo++;

		return new TokenContext(token, parentPath, query, replaceFrom, replaceTo);
	}


	private ActionCursorContext actionCursorContext(String text, int cursor) {
		int depth = 0;
		char quote = 0;
		boolean escaped = false;
		for (int index = 0; index < cursor; index++) {
			char value = text.charAt(index);
			if (quote != 0) {
				if (escaped) {
					escaped = false;
					continue;
				}
				if (value == '\\') {
					escaped = true;
				} else if (value == quote) {
					quote = 0;
				}
				continue;
			}

			if (value == '\'' || value == '"') {
				quote = value;
			} else if (value == '(') {
				depth++;
			} else if (value == ')') {
				depth = Math.max(0, depth - 1);
			} else if ((value == ';' || value == '\n') && depth == 0) {
				quote = 0;
				escaped = false;
			}
		}
		return new ActionCursorContext(depth > 0, quote != 0);
	}


	private boolean isIdentifierCharacter(char value) {
		return Character.isLetterOrDigit(value) || value == '_' || value == '-' || value == '(' || value == ')';
	}


	private List<String> safeVariables(List<String> variables) {
		if (variables == null || variables.isEmpty()) return List.of();
		return variables.stream()
				.filter(Objects::nonNull)
				.map(String::trim)
				.map(value -> value.startsWith("var.") ? value.substring(4) : value)
				.filter(value -> IDENTIFIER.matcher(value).matches())
				.distinct()
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.toList();
	}


	private boolean matches(String name, String description, String query) {
		if (query == null || query.isBlank()) return true;
		return (name != null && name.toLowerCase(Locale.ROOT).contains(query))
				|| (description != null && description.toLowerCase(Locale.ROOT).contains(query));
	}


	private boolean isPotentiallyNavigable(JavaType type) {
		if (type == null) return false;
		if (type.isCollectionLikeType() || type.isArrayType() || type.isMapLikeType()) return true;
		if (isString(type)) return true;
		if (type.getRawClass() != null && Temporal.class.isAssignableFrom(type.getRawClass())) return true;
		return !isSimple(type);
	}


	private String kind(JavaType type) {
		if (type == null) return "UNKNOWN";
		if (type.isCollectionLikeType() || type.isArrayType()) return "COLLECTION";
		if (type.isMapLikeType()) return "MAP";
		if (type.isEnumType()) return "ENUM";
		if (isString(type)) return "STRING";
		if (isBoolean(type)) return "BOOLEAN";
		if (isNumber(type)) return "NUMBER";
		if (isTemporal(type)) return "DATE_TIME";
		if (isSimple(type)) return "VALUE";
		return "OBJECT";
	}


	private boolean isSimple(JavaType type) {
		if (type == null) return true;
		Class<?> raw = type.getRawClass();
		return raw.isPrimitive()
				|| raw.isEnum()
				|| CharSequence.class.isAssignableFrom(raw)
				|| Number.class.isAssignableFrom(raw)
				|| Boolean.class.isAssignableFrom(raw)
				|| Character.class.isAssignableFrom(raw)
				|| UUID.class.isAssignableFrom(raw)
				|| Date.class.isAssignableFrom(raw)
				|| Temporal.class.isAssignableFrom(raw)
				|| TemporalAmount.class.isAssignableFrom(raw)
				|| BigDecimal.class.isAssignableFrom(raw)
				|| BigInteger.class.isAssignableFrom(raw);
	}


	private boolean isString(JavaType type) {
		return type != null && CharSequence.class.isAssignableFrom(type.getRawClass());
	}


	private boolean isBoolean(JavaType type) {
		if (type == null) return false;
		Class<?> raw = type.getRawClass();
		return raw == boolean.class || raw == Boolean.class;
	}


	private boolean isNumber(JavaType type) {
		if (type == null) return false;
		Class<?> raw = type.getRawClass();
		return raw.isPrimitive() && raw != boolean.class && raw != char.class
				|| Number.class.isAssignableFrom(raw);
	}


	private boolean isTemporal(JavaType type) {
		if (type == null) return false;
		Class<?> raw = type.getRawClass();
		return Date.class.isAssignableFrom(raw)
				|| Temporal.class.isAssignableFrom(raw)
				|| TemporalAmount.class.isAssignableFrom(raw);
	}


	private String displayType(JavaType type) {
		if (type == null) return "Object";
		if (type.isCollectionLikeType() || type.isArrayType()) {
			JavaType content = type.getContentType();
			return "List<" + (content == null ? "Object" : displayType(content)) + ">";
		}
		if (type.isMapLikeType()) {
			JavaType valueType = type.getContentType();
			return "Map<" + (valueType == null ? "Object" : displayType(valueType)) + ">";
		}
		Class<?> raw = type.getRawClass();
		return raw == null ? type.toCanonical() : raw.getSimpleName();
	}


	private String humanize(String name) {
		if (name == null || name.isBlank()) return "";
		String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ');
		return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
	}


	private int propertyOrder(String name) {
		if ("id".equals(name)) return 0;
		if ("name".equals(name) || "title".equals(name) || "type".equals(name)) return 1;
		return 10;
	}


	private void register(Map<String, RootDefinition> target, String name, String label, JavaType type) {
		target.put(name, new RootDefinition(name, label, type));
	}


	private record RootDefinition(String name, String label, JavaType type) {}

	private record PropertyDefinition(
			String name,
			String description,
			String kind,
			String typeName,
			boolean hasChildren,
			JavaType javaType
	) {}

	private record MethodDefinition(
			String expressionName,
			String javaSignature,
			String declaringType,
			List<String> parameterTypes,
			JavaType returnType
	) {}

	private record MemberDefinition(
			String insertText,
			String queryName,
			String label,
			String description,
			String kind,
			String typeName,
			boolean hasChildren,
			boolean callable,
			boolean appendDot,
			int caretOffset,
			List<Map<String, Object>> parameters
	) {}

	private record ActionCursorContext(boolean insideArguments, boolean insideString) {}

	private record TokenContext(
			String token,
			String parentPath,
			String query,
			int replaceFrom,
			int replaceTo
	) {}
}
