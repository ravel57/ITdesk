package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.*;

import java.time.*;
import java.util.*;


@Service
@RequiredArgsConstructor
public class TaskService {

	private final TaskRepository taskRepository;
	private final TaskTypeRepository taskTypeRepository;
	private final ClientRepository clientsRepository;
	private final MessageRepository messageRepository;
	private final OrganizationService organizationService;
	private final SlaRepository slaRepository;
	private final GlobalSearchService globalSearchService;
	private final EventPublisher eventPublisher;
	private final SlaPauseRepository slaPauseRepository;
	private final AppSettingsRepository appSettingsRepository;
	private final ObjectMapper objectMapper;
	private final EntityManager entityManager;
	private final UserService userService;

	private static final String FREEZE_SLA_PAUSE_REASON = "Заявка заморожена";
	private static final String CLOSED_SLA_PAUSE_REASON = "Заявка закрыта";
	private static final String MANUAL_SLA_PAUSE_REASON = "Ручная пауза SLA";
	private static final String AUTO_NON_WORKING_TIME_SLA_PAUSE_REASON = "Авто-пауза SLA: нерабочее время";
	private final UserNotificationService userNotificationService;


	@Transactional(readOnly = true)
	public Map<String, Object> getTasksPage(Map<String, Object> request) {
		int page = Math.max(1, getIntRequestValue(request, "page", 1));
		int size = Math.max(1, Math.min(100, getIntRequestValue(request, "size", 30)));
		boolean includeCompleted = getBooleanRequestValue(request, "includeCompleted", false);
		String search = normalizeSearch(getStringRequestValue(request, "search", ""));
		String filterJoinOperator = getStringRequestValue(request, "filterJoinOperator", "AND");
		if (!"OR".equals(filterJoinOperator)) {
			filterJoinOperator = "AND";
		}
		String sortSlug = getStringRequestValue(request, "sortSlug", "creating");
		boolean ascendingSort = getBooleanRequestValue(request, "ascendingSort", false);
		List<Map<String, Object>> filterChain = getFilterChainRequestValue(request);
		List<Map<String, Object>> requiredFilterChain = getRequestFilterChainValue(request, "requiredFilterChain");
		Long clientId = getLongRequestValue(request, "clientId", null);
		TaskPageQueryContext queryContext = buildTaskPageQueryContext(
				includeCompleted,
				search,
				filterChain,
				requiredFilterChain,
				filterJoinOperator,
				clientId
		);
		long totalElements = countTaskPageRows(queryContext);
		int totalPages = totalElements == 0
				? 0
				: (int) Math.ceil((double) totalElements / (double) size);

		List<TaskWithClient> pageRows = findTaskPageRows(
				queryContext,
				sortSlug,
				ascendingSort,
				page,
				size
		);
		List<Map<String, Object>> tasks = pageRows.stream()
				.map(item -> toTaskPageDto(item.task(), item.client()))
				.toList();
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("tasks", tasks);
		response.put("page", page);
		response.put("size", size);
		response.put("totalElements", totalElements);
		response.put("totalPages", totalPages);
		response.put("isEnd", page >= totalPages || tasks.isEmpty());
		return response;
	}


	@Transactional(readOnly = true)
	public Map<Long, Map<String, Object>> getOrganizationTaskStats() {
		Map<String, Object> params = new HashMap<>();

		StringBuilder jpql = new StringBuilder("""
				select
				    organization.id,
				    count(t.id),
				    sum(case
				        when coalesce(t.completed, false) = false
				          and coalesce(t.frozen, false) = false
				        then 1
				        else 0
				    end)
				from Client c
				join c.tasks t
				left join c.organization organization
				where organization.id is not null
				""");

		addCurrentUserAccessCondition(jpql, params);

		jpql.append("""
				group by organization.id
				""");

		Query query = entityManager.createQuery(jpql.toString());
		applyTaskPageQueryParams(query, params);

		@SuppressWarnings("unchecked")
		List<Object[]> rows = query.getResultList();

		Map<Long, Map<String, Object>> result = new LinkedHashMap<>();

		for (Object[] row : rows) {
			if (row == null || row.length < 3 || row[0] == null) {
				continue;
			}

			Long organizationId = ((Number) row[0]).longValue();
			Long totalTasks = row[1] instanceof Number number ? number.longValue() : 0L;
			Long openTasks = row[2] instanceof Number number ? number.longValue() : 0L;

			Map<String, Object> stats = new LinkedHashMap<>();
			stats.put("organizationId", organizationId);
			stats.put("totalTasks", totalTasks);
			stats.put("openTasks", openTasks);

			result.put(organizationId, stats);
		}

		return result;
	}


	private record TaskPageQueryContext(String fromWhere, Map<String, Object> params) {
	}


	@SuppressWarnings("unchecked")
	private List<TaskWithClient> findTaskPageRows(
			TaskPageQueryContext queryContext,
			String sortSlug,
			boolean ascendingSort,
			int page,
			int size
	) {
		String jpql = """
				select t, c
				%s
				%s
				""".formatted(
				queryContext.fromWhere(),
				buildTaskPageOrderBy(sortSlug, ascendingSort)
		);

		Query query = entityManager.createQuery(jpql);
		applyTaskPageQueryParams(query, queryContext.params());

		query.setFirstResult(Math.max(0, (page - 1) * size));
		query.setMaxResults(size);

		List<Object[]> rows = query.getResultList();

		return rows.stream()
				.map(row -> new TaskWithClient((Task) row[0], (Client) row[1]))
				.toList();
	}


	private long countTaskPageRows(TaskPageQueryContext queryContext) {
		String jpql = """
				select count(distinct t.id)
				%s
				""".formatted(queryContext.fromWhere());

		Query query = entityManager.createQuery(jpql);
		applyTaskPageQueryParams(query, queryContext.params());

		Object result = query.getSingleResult();

		if (result instanceof Number number) {
			return number.longValue();
		}

		return 0L;
	}


	private void applyTaskPageQueryParams(Query query, Map<String, Object> params) {
		params.forEach(query::setParameter);
	}


	private TaskPageQueryContext buildTaskPageQueryContext(
			boolean includeCompleted,
			String search,
			List<Map<String, Object>> filterChain,
			List<Map<String, Object>> requiredFilterChain,
			String filterJoinOperator,
			Long clientId
	) {
		Map<String, Object> params = new HashMap<>();

		StringBuilder fromWhere = new StringBuilder("""
				from Client c
				join c.tasks t
				left join t.priority priority
				left join t.status taskStatus
				left join t.type taskType
				left join t.executor executor
				left join t.sla sla
				left join c.organization organization
				where 1 = 1
				""");

		addCurrentUserAccessCondition(fromWhere, params);
		if (clientId != null) {
			params.put("taskPageClientId", clientId);
			fromWhere.append(" and c.id = :taskPageClientId ");
		}
		if (!includeCompleted) {
			fromWhere.append("""
					  and coalesce(t.completed, false) = false
					  and coalesce(t.frozen, false) = false
					""");
		}
		if (search != null && !search.isBlank()) {
			params.put("taskPageSearch", "%" + search + "%");
			fromWhere.append("""
					  and (
					       lower(coalesce(t.name, '')) like :taskPageSearch
					    or str(t.id) like :taskPageSearch
					    or lower(coalesce(priority.name, '')) like :taskPageSearch
					    or lower(coalesce(taskStatus.name, '')) like :taskPageSearch
					    or lower(coalesce(taskType.type, '')) like :taskPageSearch
					    or lower(coalesce(organization.name, '')) like :taskPageSearch
					    or lower(concat(concat(coalesce(c.lastname, ''), ' '), coalesce(c.firstname, ''))) like :taskPageSearch
					  )
					""");
		}
		List<String> filterConditions = buildTaskPageFilterConditions(filterChain, params, 0);
		if (!filterConditions.isEmpty()) {
			String joiner = "OR".equals(filterJoinOperator) ? " or " : " and ";
			fromWhere.append(" and (");
			fromWhere.append(String.join(joiner, filterConditions));
			fromWhere.append(") ");
		}
		List<String> requiredFilterConditions = buildTaskPageFilterConditions(requiredFilterChain, params, 1000);
		if (!requiredFilterConditions.isEmpty()) {
			fromWhere.append(" and (");
			fromWhere.append(String.join(" and ", requiredFilterConditions));
			fromWhere.append(") ");
		}
		return new TaskPageQueryContext(fromWhere.toString(), params);
	}


	private void addCurrentUserAccessCondition(StringBuilder fromWhere, Map<String, Object> params) {
		List<Long> availableOrganizationIds = getCurrentUserAvailableOrganizationIdsForTaskPage();

		if (availableOrganizationIds == null) {
			return;
		}

		if (availableOrganizationIds.isEmpty()) {
			fromWhere.append(" and 1 = 0 ");
			return;
		}

		params.put("availableOrganizationIds", availableOrganizationIds);
		fromWhere.append(" and organization.id in :availableOrganizationIds ");
	}


	private List<Long> getCurrentUserAvailableOrganizationIdsForTaskPage() {
		User currentUser = userService.getCurrentUser();

		if (currentUser == null) {
			return List.of();
		}

		Collection<Organization> availableOrganizations = currentUser.getAvailableOrganizations();

		if (availableOrganizations != null && !availableOrganizations.isEmpty()) {
			return availableOrganizations.stream()
					.filter(Objects::nonNull)
					.map(Organization::getId)
					.filter(Objects::nonNull)
					.toList();
		}

		if (hasTaskPageRole(currentUser, "ADMIN") || hasTaskPageRole(currentUser, "OPERATOR")) {
			return null;
		}

		return List.of();
	}


	private boolean hasTaskPageRole(User user, String role) {
		if (user == null || user.getAuthorities() == null) {
			return false;
		}

		return user.getAuthorities().stream()
				.filter(Objects::nonNull)
				.map(Object::toString)
				.anyMatch(authority -> authority.contains(role));
	}


	private List<String> buildTaskPageFilterConditions(
			List<Map<String, Object>> filterChain,
			Map<String, Object> params,
			int startIndex
	) {
		if (filterChain == null || filterChain.isEmpty()) {
			return List.of();
		}

		List<String> conditions = new ArrayList<>();
		int index = startIndex;

		for (Map<String, Object> filter : filterChain) {
			if (!isActiveTaskPageFilter(filter)) {
				continue;
			}

			String condition = buildTaskPageFilterCondition(filter, params, index);

			if (condition != null && !condition.isBlank()) {
				conditions.add("(" + condition + ")");
				index++;
			}
		}

		return conditions;
	}


	private String buildTaskPageFilterCondition(
			Map<String, Object> filter,
			Map<String, Object> params,
			int index
	) {
		String slug = getTaskPageFilterSlug(filter);
		List<String> selectedOptions = getSelectedFilterOptions(filter);

		return switch (slug) {
			case "executor" -> buildExecutorTaskPageFilterCondition(selectedOptions, params, index);
			case "tag" -> buildInTaskPageFilterCondition(
					"exists (select tag.id from t.tags tag where lower(tag.name) in :taskPageTagNames" + index + ")",
					"taskPageTagNames" + index,
					selectedOptions,
					params
			);
			case "organization" -> buildInTaskPageFilterCondition(
					"lower(organization.name) in :taskPageOrganizationNames" + index,
					"taskPageOrganizationNames" + index,
					selectedOptions,
					params
			);
			case "priority" -> buildInTaskPageFilterCondition(
					"lower(priority.name) in :taskPagePriorityNames" + index,
					"taskPagePriorityNames" + index,
					selectedOptions,
					params
			);
			case "status" -> buildInTaskPageFilterCondition(
					"lower(taskStatus.name) in :taskPageStatusNames" + index,
					"taskPageStatusNames" + index,
					selectedOptions,
					params
			);
			case "client" -> buildInTaskPageFilterCondition(
					"lower(concat(concat(coalesce(c.lastname, ''), ' '), coalesce(c.firstname, ''))) in :taskPageClientNames" + index,
					"taskPageClientNames" + index,
					selectedOptions,
					params
			);
			case "type" -> buildInTaskPageFilterCondition(
					"lower(taskType.type) in :taskPageTypeNames" + index,
					"taskPageTypeNames" + index,
					selectedOptions,
					params
			);
			case "createdAt" -> buildDateRangeTaskPageFilterCondition(
					"t.createdAt",
					filter,
					params,
					index
			);
			case "lastActivity" -> buildDateRangeTaskPageFilterCondition(
					"t.lastActivity",
					filter,
					params,
					index
			);
			case "deadline" -> buildDeadlineTaskPageFilterCondition(filter, params, index);
			default -> null;
		};
	}


	private String buildExecutorTaskPageFilterCondition(
			List<String> selectedOptions,
			Map<String, Object> params,
			int index
	) {
		if (selectedOptions == null || selectedOptions.isEmpty()) {
			return null;
		}

		List<String> conditions = new ArrayList<>();
		List<String> executorNames = new ArrayList<>();

		for (String option : selectedOptions) {
			String value = Objects.toString(option, "").trim();

			if (value.isBlank()) {
				continue;
			}

			if ("Без исполнителя".equals(value)) {
				conditions.add("executor is null");
				continue;
			}

			if ("Вы".equals(value)) {
				User currentUser = userService.getCurrentUser();

				if (currentUser != null && currentUser.getId() != null) {
					String paramName = "taskPageCurrentExecutorId" + index;
					params.put(paramName, currentUser.getId());
					conditions.add("executor.id = :" + paramName);
				}

				continue;
			}

			executorNames.add(value.toLowerCase(Locale.ROOT));
		}

		if (!executorNames.isEmpty()) {
			String paramName = "taskPageExecutorNames" + index;
			params.put(paramName, executorNames);
			conditions.add("""
					(
					     lower(concat(concat(coalesce(executor.firstname, ''), ' '), coalesce(executor.lastname, ''))) in :%s
					  or lower(coalesce(executor.username, '')) in :%s
					)
					""".formatted(paramName, paramName));
		}

		if (conditions.isEmpty()) {
			return null;
		}

		return String.join(" or ", conditions);
	}


	private String buildInTaskPageFilterCondition(
			String condition,
			String paramName,
			List<String> selectedOptions,
			Map<String, Object> params
	) {
		List<String> values = normalizeTaskPageFilterValues(selectedOptions);

		if (values.isEmpty()) {
			return null;
		}

		params.put(paramName, values);
		return condition;
	}


	private List<String> normalizeTaskPageFilterValues(List<String> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}

		return values.stream()
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(value -> !value.isBlank())
				.map(value -> value.toLowerCase(Locale.ROOT))
				.toList();
	}


	private String buildDeadlineTaskPageFilterCondition(
			Map<String, Object> filter,
			Map<String, Object> params,
			int index
	) {
		String selectedOption = Objects.toString(filter.get("selectedOptions"), "").trim();

		if (selectedOption.isBlank()) {
			return null;
		}

		try {
			LocalDateTime filterDate = LocalDateTime.parse(
					selectedOption,
					java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
			);

			String paramName = "taskPageDeadline" + index;
			params.put(paramName, filterDate.atZone(ZoneId.systemDefault()));

			boolean beforeDeadline = getBooleanRequestValue(filter, "isBeforeDeadline", false);

			if (beforeDeadline) {
				return "t.deadline <= :" + paramName;
			}

			return "t.deadline >= :" + paramName;
		} catch (Exception ignored) {
			return null;
		}
	}


	private String buildDateRangeTaskPageFilterCondition(
			String fieldName,
			Map<String, Object> filter,
			Map<String, Object> params,
			int index
	) {
		Map<String, String> range = getTaskPageDateRangeSelectedOptions(filter.get("selectedOptions"));

		ZonedDateTime from = parseTaskPageDateStart(range.get("from"));
		ZonedDateTime to = parseTaskPageDateEnd(range.get("to"));

		if (from == null && to == null) {
			return null;
		}

		List<String> conditions = new ArrayList<>();

		if (from != null) {
			String paramName = "taskPageDateFrom" + index;
			params.put(paramName, from);
			conditions.add(fieldName + " >= :" + paramName);
		}

		if (to != null) {
			String paramName = "taskPageDateTo" + index;
			params.put(paramName, to);
			conditions.add(fieldName + " <= :" + paramName);
		}

		return String.join(" and ", conditions);
	}


	private boolean isDateRangeTaskPageFilter(String slug) {
		return "createdAt".equals(slug) || "lastActivity".equals(slug);
	}


	private boolean isDateRangeFilterSelected(Object selectedOptions) {
		Map<String, String> range = getTaskPageDateRangeSelectedOptions(selectedOptions);

		return !Objects.toString(range.get("from"), "").isBlank()
				|| !Objects.toString(range.get("to"), "").isBlank();
	}


	@SuppressWarnings("unchecked")
	private Map<String, String> getTaskPageDateRangeSelectedOptions(Object selectedOptions) {
		if (!(selectedOptions instanceof Map<?, ?> map)) {
			return Map.of(
					"from", "",
					"to", ""
			);
		}
		return Map.of(
				"from", Objects.toString(map.get("from"), "").trim(),
				"to", Objects.toString(map.get("to"), "").trim()
		);
	}


	private ZonedDateTime parseTaskPageDateStart(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			LocalDate date = LocalDate.parse(
					value.trim(),
					java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
			);
			return date.atStartOfDay(ZoneId.systemDefault());
		} catch (Exception ignored) {
			return null;
		}
	}


	private ZonedDateTime parseTaskPageDateEnd(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			LocalDate date = LocalDate.parse(
					value.trim(),
					java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
			);
			return date.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault());
		} catch (Exception ignored) {
			return null;
		}
	}


	private String buildTaskPageOrderBy(String sortSlug, boolean ascendingSort) {
		String direction = getTaskPageSortDirection(sortSlug, ascendingSort);

		return switch (Objects.toString(sortSlug, "")) {
			case "deadline" -> """
					order by
					    case when t.deadline is null then 1 else 0 end asc,
					    t.deadline %s,
					    t.id desc
					""".formatted(direction);

			case "priority" -> """
					order by
					    case when priority.orderNumber is null then 1 else 0 end asc,
					    priority.orderNumber %s,
					    t.id desc
					""".formatted(direction);

			case "sla" -> """
					order by
					    case when sla.startDate is null then 1 else 0 end asc,
					    sla.startDate %s,
					    t.id desc
					""".formatted(direction);

			case "status" -> """
					order by
					    case when taskStatus.orderNumber is null then 1 else 0 end asc,
					    taskStatus.orderNumber %s,
					    t.id desc
					""".formatted(direction);

			case "creating" -> """
					order by
					    case when t.createdAt is null then 1 else 0 end asc,
					    t.createdAt %s,
					    t.id desc
					""".formatted(direction);

			default -> """
					order by
					    case when t.createdAt is null then 1 else 0 end asc,
					    t.createdAt desc,
					    t.id desc
					""";
		};
	}


	private String getTaskPageSortDirection(String sortSlug, boolean ascendingSort) {
		if ("priority".equals(sortSlug) || "status".equals(sortSlug)) {
			return ascendingSort ? "desc" : "asc";
		}

		return ascendingSort ? "asc" : "desc";
	}


	private record TaskWithClient(Task task, Client client) {
	}


	private int getIntRequestValue(Map<String, Object> request, String key, int defaultValue) {
		Object value = request == null ? null : request.get(key);
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value instanceof String stringValue) {
			try {
				return Integer.parseInt(stringValue);
			} catch (NumberFormatException ignored) {
				return defaultValue;
			}
		}
		return defaultValue;
	}


	private Long getLongRequestValue(Map<String, Object> request, String key, Long defaultValue) {
		Object value = request == null ? null : request.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof String stringValue) {
			try {
				return Long.parseLong(stringValue);
			} catch (NumberFormatException ignored) {
				return defaultValue;
			}
		}
		return defaultValue;
	}


	private boolean getBooleanRequestValue(Map<String, Object> request, String key, boolean defaultValue) {
		Object value = request == null ? null : request.get(key);
		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		if (value instanceof String stringValue) {
			return Boolean.parseBoolean(stringValue);
		}
		return defaultValue;
	}


	private String getStringRequestValue(Map<String, Object> request, String key, String defaultValue) {
		Object value = request == null ? null : request.get(key);
		return value == null ? defaultValue : Objects.toString(value, defaultValue);
	}


	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> getFilterChainRequestValue(Map<String, Object> request) {
		Object value = request == null ? null : request.get("filterChain");
		if (!(value instanceof List<?> filters)) {
			return List.of();
		}
		return filters.stream()
				.filter(Map.class::isInstance)
				.map(filter -> (Map<String, Object>) filter)
				.toList();
	}


	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> getRequestFilterChainValue(Map<String, Object> request, String key) {
		Object value = request == null ? null : request.get(key);
		if (!(value instanceof List<?> filters)) {
			return List.of();
		}
		return filters.stream()
				.filter(Map.class::isInstance)
				.map(filter -> (Map<String, Object>) filter)
				.toList();
	}


	private String normalizeSearch(String value) {
		return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
	}


	private boolean canCurrentUserAccessClient(Client client) {
		try {
			userService.assertCurrentUserCanAccessClient(client);
			return true;
		} catch (Exception ignored) {
			return false;
		}
	}


	private boolean isTaskPageItemVisible(Task task, boolean includeCompleted) {
		return includeCompleted || (!Boolean.TRUE.equals(task.getFrozen()) && !Boolean.TRUE.equals(task.getCompleted()));
	}


	private boolean isTaskPageItemMatchesSearch(Task task, Client client, String search) {
		if (search == null || search.isBlank()) {
			return true;
		}
		return containsSearch(task.getName(), search)
				|| containsSearch(task.getId(), search)
				|| containsSearch(getName(task.getPriority()), search)
				|| containsSearch(getName(task.getStatus()), search)
				|| containsSearch(getTaskTypeName(task.getType()), search)
				|| containsSearch(getClientFullName(client), search)
				|| containsSearch(client == null || client.getOrganization() == null ? "" : client.getOrganization().getName(), search)
				|| containsSearch(getChecklistSearchText(task.getChecklist()), search);
	}


	private boolean containsSearch(Object value, String search) {
		return Objects.toString(value, "").toLowerCase(Locale.ROOT).contains(search);
	}


	private String getChecklistSearchText(List<ChecklistItem> checklist) {
		if (checklist == null || checklist.isEmpty()) {
			return "";
		}
		return checklist.stream()
				.filter(item -> item != null && item.getText() != null)
				.map(ChecklistItem::getText)
				.toList()
				.toString();
	}


	private boolean isTaskPageItemMatchesFilters(
			Task task,
			Client client,
			List<Map<String, Object>> filterChain,
			String filterJoinOperator
	) {
		List<Map<String, Object>> activeFilters = filterChain.stream()
				.filter(this::isActiveTaskPageFilter)
				.toList();
		if (activeFilters.isEmpty()) {
			return true;
		}
		if ("OR".equals(filterJoinOperator)) {
			return activeFilters.stream().anyMatch(filter -> isTaskPageItemMatchesFilter(task, client, filter));
		}
		return activeFilters.stream().allMatch(filter -> isTaskPageItemMatchesFilter(task, client, filter));
	}


	private boolean isActiveTaskPageFilter(Map<String, Object> filter) {
		String slug = getTaskPageFilterSlug(filter);
		Object selectedOptions = filter.get("selectedOptions");
		if ("deadline".equals(slug)) {
			return selectedOptions != null && !Objects.toString(selectedOptions, "").isBlank();
		}
		if (isDateRangeTaskPageFilter(slug)) {
			return isDateRangeFilterSelected(selectedOptions);
		}
		return selectedOptions instanceof List<?> list && !list.isEmpty();
	}


	private boolean isTaskPageItemMatchesFilter(Task task, Client client, Map<String, Object> filter) {
		String slug = getTaskPageFilterSlug(filter);
		List<String> selectedOptions = getSelectedFilterOptions(filter);
		return switch (slug) {
			case "executor" -> selectedOptions.contains(getExecutorFilterName(task));
			case "tag" -> task.getTags() != null && task.getTags().stream()
					.filter(Objects::nonNull)
					.map(Tag::getName)
					.anyMatch(selectedOptions::contains);
			case "organization" -> client != null
					&& client.getOrganization() != null
					&& selectedOptions.contains(client.getOrganization().getName());
			case "priority" -> task.getPriority() != null && selectedOptions.contains(task.getPriority().getName());
			case "status" -> task.getStatus() != null && selectedOptions.contains(task.getStatus().getName());
			case "client" -> selectedOptions.contains(getClientFullName(client));
			case "type" -> selectedOptions.contains(getTaskTypeName(task.getType()));
			case "createdAt" -> isTaskDateMatchesRangeFilter(task.getCreatedAt(), filter);
			case "lastActivity" -> isTaskDateMatchesRangeFilter(task.getLastActivity(), filter);
			case "deadline" -> isDeadlineMatchesTaskPageFilter(task, filter);
			default -> true;
		};
	}


	private String getTaskPageFilterSlug(Map<String, Object> filter) {
		String slug = Objects.toString(filter.get("slug"), "");
		if (!slug.isBlank()) {
			return slug;
		}
		String label = Objects.toString(filter.get("label"), "");
		return switch (label) {
			case "Исполнитель" -> "executor";
			case "Тег" -> "tag";
			case "Организация" -> "organization";
			case "Приоритет" -> "priority";
			case "Статус" -> "status";
			case "Клиент" -> "client";
			case "Тип заявки" -> "type";
			case "Дата создания" -> "createdAt";
			case "Дата последней активности" -> "lastActivity";
			case "Дедлайн" -> "deadline";
			default -> "";
		};
	}


	private List<String> getSelectedFilterOptions(Map<String, Object> filter) {
		Object value = filter.get("selectedOptions");
		if (value instanceof List<?> list) {
			return list.stream()
					.map(item -> Objects.toString(item, ""))
					.filter(item -> !item.isBlank())
					.toList();
		}
		String stringValue = Objects.toString(value, "").trim();
		return stringValue.isBlank() ? List.of() : List.of(stringValue);
	}


	private String getExecutorFilterName(Task task) {
		User executor = task.getExecutor();
		if (executor == null) {
			return "Без исполнителя";
		}
		User currentUser = userService.getCurrentUser();
		if (currentUser != null && Objects.equals(currentUser.getId(), executor.getId())) {
			return "Вы";
		}
		return getUserFirstLastName(executor);
	}


	private String getUserFirstLastName(User user) {
		if (user == null) {
			return "";
		}
		String firstname = Objects.toString(user.getFirstname(), "").trim();
		String lastname = Objects.toString(user.getLastname(), "").trim();
		String fullName = ("%s %s".formatted(firstname, lastname)).trim();
		return fullName.isBlank() ? Objects.toString(user.getUsername(), "") : fullName;
	}


	private String getClientFullName(Client client) {
		if (client == null) {
			return "";
		}
		return ("%s %s".formatted(
				Objects.toString(client.getLastname(), "").trim(),
				Objects.toString(client.getFirstname(), "").trim()
		)).trim();
	}


	private boolean isDeadlineMatchesTaskPageFilter(Task task, Map<String, Object> filter) {
		if (task.getDeadline() == null) {
			return false;
		}
		String selectedOption = Objects.toString(filter.get("selectedOptions"), "").trim();
		if (selectedOption.isBlank()) {
			return true;
		}
		try {
			LocalDateTime filterDate = LocalDateTime.parse(
					selectedOption,
					java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
			);
			ZonedDateTime filterDateTime = filterDate.atZone(task.getDeadline().getZone());
			boolean beforeDeadline = getBooleanRequestValue(filter, "isBeforeDeadline", false);
			if (beforeDeadline) {
				return !task.getDeadline().isAfter(filterDateTime);
			}
			return !task.getDeadline().isBefore(filterDateTime);
		} catch (Exception ignored) {
			return true;
		}
	}


	private boolean isTaskDateMatchesRangeFilter(ZonedDateTime taskDate, Map<String, Object> filter) {
		if (taskDate == null) {
			return false;
		}
		Map<String, String> range = getTaskPageDateRangeSelectedOptions(filter.get("selectedOptions"));
		ZonedDateTime from = parseTaskPageDateStart(range.get("from"));
		ZonedDateTime to = parseTaskPageDateEnd(range.get("to"));
		if (from != null && taskDate.isBefore(from)) {
			return false;
		}
		return to == null || !taskDate.isAfter(to);
	}


	private Comparator<TaskWithClient> getTaskPageComparator(String sortSlug, boolean ascendingSort) {
		Comparator<TaskWithClient> comparator = switch (Objects.toString(sortSlug, "")) {
			case "deadline" ->
					Comparator.comparing(item -> item.task().getDeadline(), nullSafeZonedDateTimeComparator());
			case "priority" -> Comparator.comparingInt(item -> getPriorityOrderNumber(item.task().getPriority()));
			case "sla" ->
					Comparator.comparing(item -> getTaskSlaDeadline(item.task()), nullSafeZonedDateTimeComparator());
			case "status" -> Comparator.comparingInt(item -> getStatusOrderNumber(item.task().getStatus()));
			case "creating" ->
					Comparator.comparing(item -> item.task().getCreatedAt(), nullSafeZonedDateTimeComparator());
			default -> Comparator.comparing(item -> item.task().getCreatedAt(), nullSafeZonedDateTimeComparator());
		};

		if ("priority".equals(sortSlug) || "status".equals(sortSlug)) {
			return ascendingSort ? comparator.reversed() : comparator;
		}
		return ascendingSort ? comparator : comparator.reversed();
	}


	private int getPriorityOrderNumber(Priority priority) {
		if (priority == null) {
			return Integer.MAX_VALUE;
		}
		Integer orderNumber = priority.getOrderNumber();
		return orderNumber == null ? Integer.MAX_VALUE : orderNumber;
	}


	private int getStatusOrderNumber(Status status) {
		if (status == null) {
			return Integer.MAX_VALUE;
		}
		Integer orderNumber = status.getOrderNumber();
		return orderNumber == null ? Integer.MAX_VALUE : orderNumber;
	}


	private Comparator<ZonedDateTime> nullSafeZonedDateTimeComparator() {
		return Comparator.nullsLast(Comparator.naturalOrder());
	}


	private ZonedDateTime getTaskSlaDeadline(Task task) {
		if (task == null || task.getSla() == null || task.getSla().getStartDate() == null || task.getSla().getDuration() == null) {
			return null;
		}
		return task.getSla().getStartDate().plus(task.getSla().getDuration());
	}


	private Map<String, Object> toTaskPageDto(Task task, Client client) {
		Map<String, Object> taskDto = objectMapper.convertValue(task, new TypeReference<>() {
		});
		putZonedDateTime(taskDto, "createdAt", task.getCreatedAt());
		putZonedDateTime(taskDto, "lastActivity", task.getLastActivity());
		putZonedDateTime(taskDto, "deadline", task.getDeadline());
		putZonedDateTime(taskDto, "closedAt", task.getClosedAt());
		putZonedDateTime(taskDto, "frozenFrom", task.getFrozenFrom());
		putZonedDateTime(taskDto, "frozenUntil", task.getFrozenUntil());
		if (task.getSla() != null && taskDto.get("sla") instanceof Map<?, ?> rawSlaMap) {
			@SuppressWarnings("unchecked")
			Map<String, Object> slaMap = (Map<String, Object>) rawSlaMap;
			putZonedDateTime(slaMap, "startDate", task.getSla().getStartDate());
		}
		taskDto.put("client", toClientPageDto(client));
		return taskDto;
	}


	private void putZonedDateTime(Map<String, Object> dto, String key, ZonedDateTime value) {
		dto.put(key, value == null ? null : value.toString());
	}


	private Map<String, Object> toClientPageDto(Client client) {
		Map<String, Object> clientDto = new LinkedHashMap<>();
		if (client == null) {
			return clientDto;
		}
		clientDto.put("id", client.getId());
		clientDto.put("firstname", client.getFirstname());
		clientDto.put("lastname", client.getLastname());
		clientDto.put("organization", toOrganizationPageDto(client.getOrganization()));
		return clientDto;
	}


	private Map<String, Object> toOrganizationPageDto(Organization organization) {
		if (organization == null) {
			return null;
		}
		Map<String, Object> organizationDto = new LinkedHashMap<>();
		organizationDto.put("id", organization.getId());
		organizationDto.put("name", organization.getName());
		return organizationDto;
	}


	@Transactional(readOnly = true)
	public List<TaskType> getTaskTypes() {
		return taskTypeRepository.findAll().stream().sorted().toList();
	}


	@Transactional(readOnly = true)
	public TaskType getTaskType(Long id) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		return taskTypeRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Тип заявки не найден: %d".formatted(id)));
	}


	@Transactional
	public Task newTask(Long clientId, Task task) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		if (task == null) {
			throw new IllegalArgumentException("task must not be null");
		}
		Client client = getClientForCurrentUser(clientId);
		return createTaskForClient(client, task);
	}


	@Transactional
	public Task newTaskForSystem(Long clientId, Task task) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		if (task == null) {
			throw new IllegalArgumentException("task must not be null");
		}
		Client client = clientsRepository.findById(clientId).orElseThrow();
		return createTaskForClient(client, task);
	}


	@Transactional
	public Task updateTask(Long clientId, Task task) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		if (task == null) {
			throw new IllegalArgumentException("task must not be null");
		}
		if (task.getId() == null) {
			throw new IllegalArgumentException("task.id must not be null");
		}
		prepareTaskBeforeSave(task);
		Task olderTask = taskRepository.findById(task.getId()).orElseThrow();
		Client client = getClientForCurrentUser(clientId);
		FrozenStatus frozenStatus = FrozenStatus.getInstance();
		CompletedStatus completedStatus = CompletedStatus.getInstance();
		Priority oldPriority = olderTask.getPriority();
		Status oldStatus = olderTask.getStatus();
		Status statusBeforeUpdate = olderTask.getStatus();
		Boolean oldCompleted = olderTask.getCompleted();
		Status requestedStatus = task.getStatus();
		boolean requestedStatusIsFrozen = isSameStatus(requestedStatus, frozenStatus);
		boolean requestedStatusIsCompleted = isSameStatus(requestedStatus, completedStatus);
		boolean requestedStatusActuallyChanged = requestedStatus != null && !isSameStatus(oldStatus, requestedStatus);
		boolean closing = !Boolean.TRUE.equals(oldCompleted) && Boolean.TRUE.equals(task.getCompleted());
		validateExecutorIsPresentWhenClosing(task, requestedStatus, completedStatus);
		boolean oldFrozen = Boolean.TRUE.equals(olderTask.getFrozen());
		User oldExecutor = olderTask.getExecutor();
		ZonedDateTime oldDeadline = olderTask.getDeadline();
		ZonedDateTime oldFrozenUntil = olderTask.getFrozenUntil();
		Set<Tag> oldTags = new HashSet<>(Objects.requireNonNullElse(olderTask.getTags(), Collections.emptyList()));
		List<Map<String, Object>> changes = new ArrayList<>();
		String statusChangeReason = normalizeStatusChangeReason(task.getStatusChangeReason());
		addChange(changes, "name", "Название", olderTask.getName(), task.getName());
		addChange(changes, "description", "Описание", olderTask.getDescription(), task.getDescription());
		addChange(changes, "type", "Тип", getTaskTypeName(olderTask.getType()), getTaskTypeName(task.getType()));
		addChange(changes, "checklist", "Чек-лист", checklistToHistoryValue(olderTask.getChecklist()), checklistToHistoryValue(task.getChecklist()));
		addChange(changes, "deadline", "Дедлайн", olderTask.getDeadline(), task.getDeadline());
		addChange(changes, "executor", "Исполнитель", getUserDisplayName(olderTask.getExecutor()), getUserDisplayName(task.getExecutor()));
		addChange(changes, "priority", "Приоритет", getName(olderTask.getPriority()), getName(task.getPriority()));
		addChange(changes, "status", "Статус", getName(olderTask.getStatus()), getName(task.getStatus()));
		addChange(changes, "completed", "Закрыта", olderTask.getCompleted(), task.getCompleted());
		addChange(changes, "linkedMessageId", "Связанное сообщение", olderTask.getLinkedMessageId(), task.getLinkedMessageId());
		olderTask.setName(task.getName());
		olderTask.setDescription(task.getDescription());
		olderTask.setType(task.getType());
		olderTask.setChecklist(task.getChecklist());
		olderTask.setPriority(task.getPriority());
		if (olderTask.getSla() == null || !Objects.equals(oldPriority, task.getPriority())) {
			setSla(client, olderTask);
		}
		boolean reopening = Boolean.TRUE.equals(olderTask.getCompleted()) && Boolean.FALSE.equals(task.getCompleted());
		olderTask.setDeadline(task.getDeadline());
		olderTask.setExecutor(task.getExecutor());
		olderTask.setTags(task.getTags());
		olderTask.setLinkedMessageId(task.getLinkedMessageId());
		if (task.getFrozen() != null) {
			if (task.getFrozen()) {
				olderTask.setFrozen(true);
				olderTask.setFrozenFrom(
						task.getFrozenFrom() != null
								? task.getFrozenFrom()
								: Objects.requireNonNullElse(olderTask.getFrozenFrom(), ZonedDateTime.now())
				);
				olderTask.setFrozenUntil(task.getFrozenUntil());
			} else {
				olderTask.setFrozen(false);
				olderTask.setFrozenFrom(null);
				olderTask.setFrozenUntil(null);
			}
		} else if (oldFrozen && requestedStatus != null && !requestedStatusIsFrozen) {
			olderTask.setFrozen(false);
			olderTask.setFrozenFrom(null);
			olderTask.setFrozenUntil(null);
		} else if (!oldFrozen && requestedStatusIsFrozen) {
			// Нельзя молча переводить заявку в статус «Заморожена» без срока заморозки.
			// Заморозка должна приходить отдельным действием с frozen=true и frozenUntil.
			task.setStatus(oldStatus);
			requestedStatus = oldStatus;
			requestedStatusIsFrozen = isSameStatus(requestedStatus, frozenStatus);
			requestedStatusIsCompleted = isSameStatus(requestedStatus, completedStatus);
			requestedStatusActuallyChanged = false;
		}
		olderTask.setCompleted(Boolean.TRUE.equals(task.getCompleted()));
		if (!Objects.equals(oldCompleted, task.getCompleted())) {
			if (Boolean.TRUE.equals(task.getCompleted())) {
				olderTask.setClosedAt(ZonedDateTime.now());
			} else {
				olderTask.setClosedAt(null);
			}
		}
		if (reopening) {
			olderTask.setStatus(
					olderTask.getPreviousStatus() != null
							&& !isSameStatus(olderTask.getPreviousStatus(), completedStatus)
							&& !isSameStatus(olderTask.getPreviousStatus(), frozenStatus)
							? olderTask.getPreviousStatus()
							: task.getStatus()
			);
		} else {
			olderTask.setStatus(task.getStatus());
		}
		if (!closing
				&& requestedStatusActuallyChanged
				&& task.getPreviousStatus() != null
				&& !isSameStatus(task.getPreviousStatus(), completedStatus)
				&& !isSameStatus(task.getPreviousStatus(), frozenStatus)) {
			olderTask.setPreviousStatus(task.getPreviousStatus());
		}
		if (Boolean.TRUE.equals(olderTask.getFrozen())) {
			if (requestedStatusActuallyChanged) {
				if (!isSameStatus(statusBeforeUpdate, frozenStatus) && !isSameStatus(statusBeforeUpdate, completedStatus)) {
					olderTask.setPreviousStatus(statusBeforeUpdate);
				} else if (!isSameStatus(olderTask.getStatus(), frozenStatus)
						&& !isSameStatus(olderTask.getStatus(), completedStatus)) {
					olderTask.setPreviousStatus(olderTask.getStatus());
				}
			}
			olderTask.setStatus(frozenStatus);
			olderTask.setFrozen(true);
		} else if (olderTask.getPreviousStatus() != null
				&& Objects.equals(frozenStatus.getId(), olderTask.getStatus() == null ? null : olderTask.getStatus().getId())
				&& Objects.equals(olderTask.getStatus(), frozenStatus)) {
			olderTask.setStatus(olderTask.getPreviousStatus());
		}
		if (Boolean.TRUE.equals(olderTask.getCompleted())) {
			if (closing
					&& statusBeforeUpdate != null
					&& !isSameStatus(statusBeforeUpdate, completedStatus)
					&& !isSameStatus(statusBeforeUpdate, frozenStatus)) {
				olderTask.setPreviousStatus(statusBeforeUpdate);
			} else if (!closing
					&& olderTask.getPreviousStatus() == null
					&& olderTask.getStatus() != null
					&& !isSameStatus(olderTask.getStatus(), completedStatus)
					&& !isSameStatus(olderTask.getStatus(), frozenStatus)) {
				olderTask.setPreviousStatus(olderTask.getStatus());
			}
			if (Boolean.TRUE.equals(olderTask.getFrozen())) {
				olderTask.setFrozen(false);
				olderTask.setFrozenFrom(null);
				olderTask.setFrozenUntil(null);
			}
			olderTask.setStatus(completedStatus);
			olderTask.setCompleted(true);
		} else if (olderTask.getPreviousStatus() != null
				&& Boolean.FALSE.equals(olderTask.getFrozen())
				&& !Objects.equals(olderTask.getPreviousStatus(), completedStatus)
				&& Objects.equals(olderTask.getStatus(), completedStatus)) {
			olderTask.setStatus(olderTask.getPreviousStatus());
		} else if (Objects.equals(olderTask.getStatus(), completedStatus)
				&& Objects.equals(olderTask.getStatus() == null ? null : olderTask.getStatus().getId(), completedStatus.getId())) {
			olderTask.setCompleted(false);
			olderTask.setStatus(olderTask.getPreviousStatus());
		}
		boolean newFrozen = Boolean.TRUE.equals(olderTask.getFrozen());
		if (oldFrozen != newFrozen) {
			addChange(
					changes,
					"frozen",
					"Заморозка",
					oldFrozen ? "Да" : "Нет",
					newFrozen ? "Да" : "Нет"
			);
		}
		if (!Objects.equals(oldFrozenUntil, olderTask.getFrozenUntil())) {
			addChange(
					changes,
					"frozenUntil",
					"Заморожена до",
					oldFrozenUntil,
					olderTask.getFrozenUntil()
			);
		}
		boolean statusChanged = !isSameStatus(oldStatus, olderTask.getStatus());
		boolean completedChanged = !Objects.equals(oldCompleted, olderTask.getCompleted());
		boolean frozenChanged = oldFrozen != newFrozen;
		boolean oldSlaPausedByTaskState = oldFrozen || Boolean.TRUE.equals(oldCompleted);
		boolean newSlaPausedByTaskState = newFrozen || Boolean.TRUE.equals(olderTask.getCompleted());
		if (oldSlaPausedByTaskState != newSlaPausedByTaskState) {
			addChange(
					changes,
					"slaPause",
					"SLA-пауза",
					oldSlaPausedByTaskState ? "Поставлена на паузу" : "Снята с паузы",
					newSlaPausedByTaskState ? "Поставлена на паузу" : "Снята с паузы"
			);
		}
		if (statusChangeReason != null && (statusChanged || completedChanged || frozenChanged)) {
			olderTask.setStatusChangeReason(statusChangeReason);
		}
		syncSlaPauseState(olderTask);
		olderTask.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(olderTask);
		globalSearchService.indexClient(client);
		globalSearchService.indexTask(client, savedTask);
		if (!changes.isEmpty()) {
			eventPublisher.publish(TriggerType.TASK_UPDATED, eventPayload(
					"task", savedTask,
					"client", client,
					"changes", changes
			));
		}
		if (!isSameStatus(oldStatus, savedTask.getStatus())) {
			eventPublisher.publish(TriggerType.TASK_STATUS_CHANGED, eventPayload(
					"task", savedTask,
					"client", client,
					"oldStatus", oldStatus,
					"newStatus", savedTask.getStatus(),
					"reason", statusChangeReason
			));
		}
		if (!Objects.equals(oldPriority, savedTask.getPriority())) {
			eventPublisher.publish(TriggerType.TASK_PRIORITY_CHANGED, eventPayload(
					"task", savedTask,
					"client", client,
					"oldPriority", oldPriority,
					"newPriority", savedTask.getPriority()
			));
		}
		if (!Objects.equals(oldExecutor, savedTask.getExecutor())) {
			eventPublisher.publish(TriggerType.TASK_ASSIGNEE_CHANGED, eventPayload(
					"task", savedTask,
					"client", client,
					"oldExecutor", oldExecutor,
					"newExecutor", savedTask.getExecutor()
			));
			if (savedTask.getExecutor() != null) {
				userNotificationService.send(new UserNotification(
						UserNotificationEvent.NEW_TASK,
						savedTask.getName(),
						savedTask.getExecutor().getId()
				));
			}
		}
		if (!Objects.equals(oldDeadline, savedTask.getDeadline())) {
			eventPublisher.publish(TriggerType.TASK_DUE_DATE_CHANGED, eventPayload(
					"task", savedTask,
					"client", client,
					"oldDeadline", oldDeadline,
					"newDeadline", savedTask.getDeadline()
			));
		}

		if (!Objects.equals(oldCompleted, savedTask.getCompleted()) && Boolean.TRUE.equals(savedTask.getCompleted())) {
			eventPublisher.publish(TriggerType.TASK_CLOSED, eventPayload(
					"task", savedTask,
					"client", client,
					"reason", statusChangeReason
			));
		}
		if (Boolean.TRUE.equals(oldCompleted) && !savedTask.getCompleted()) {
			eventPublisher.publish(TriggerType.TASK_REOPENED, eventPayload(
					"task", savedTask,
					"client", client,
					"reason", statusChangeReason
			));
		}
		Set<Tag> newTags = new HashSet<>(Objects.requireNonNullElse(savedTask.getTags(), Collections.emptyList()));
		for (Tag tag : newTags) {
			if (!oldTags.contains(tag)) {
				eventPublisher.publish(TriggerType.TASK_TAG_ADDED, eventPayload(
						"task", savedTask,
						"client", client,
						"tag", tag
				));
			}
		}
		for (Tag tag : oldTags) {
			if (!newTags.contains(tag)) {
				eventPublisher.publish(TriggerType.TASK_TAG_REMOVED, eventPayload(
						"task", savedTask,
						"client", client,
						"tag", tag
				));
			}
		}
		return savedTask;
	}


	private void setSla(Client client, Task task) {
		if (task == null || task.getPriority() == null) {
			return;
		}
		Map<Organization, Map<Priority, SlaValue>> slaByPriority = organizationService.getSlaByPriority();
		SlaValue slaValue = findSlaValue(
				slaByPriority,
				client == null ? null : client.getOrganization(),
				task.getPriority()
		);
		if (slaValue == null) {
			slaValue = findDefaultSlaValue(slaByPriority, task.getPriority());
		}
		Duration slaDuration = slaValue == null
				? Duration.ZERO
				: slaValue.toDuration(getWorkdayDuration());

		if (slaDuration.isZero() || slaDuration.isNegative()) {
			task.setSla(null);
			return;
		}
		Sla sla = task.getSla();
		if (sla == null) {
			sla = Sla.builder()
					.startDate(Objects.requireNonNullElse(task.getCreatedAt(), ZonedDateTime.now()))
					.build();
		}
		sla.setDuration(slaDuration);
		slaRepository.save(sla);
		task.setSla(sla);
	}


	private Duration getWorkdayDuration() {
		return appSettingsRepository.findAll().stream()
				.findFirst()
				.map(AppSettings::getWorkdayDuration)
				.orElse(Duration.ofHours(24));
	}


	private boolean shouldBlockManualResumeBecauseOfAutoNonWorkingTime() {
		AppSettings settings = appSettingsRepository.findAll().stream()
				.findFirst()
				.orElse(null);
		if (settings == null || !Boolean.TRUE.equals(settings.getWorkingTimeEnabled())) {
			return false;
		}
		try {
			ZoneId zoneId = settings.getTimezone() == null || settings.getTimezone().isBlank()
					? ZoneId.systemDefault()
					: ZoneId.of(settings.getTimezone());
			ZonedDateTime now = ZonedDateTime.now(zoneId);
			if (!isWorkingDayEnabled(settings, now.getDayOfWeek())) {
				return true;
			}
			LocalTime start = LocalTime.parse(settings.getWorkdayStart());
			LocalTime end = LocalTime.parse(settings.getWorkdayEnd());
			return !isInsideWorkingTime(now.toLocalTime(), start, end);
		} catch (Exception e) {
			return true;
		}
	}


	private boolean isInsideWorkingTime(LocalTime time, LocalTime start, LocalTime end) {
		if (start.equals(end)) {
			return true;
		}
		if (end.isAfter(start)) {
			return !time.isBefore(start) && time.isBefore(end);
		}
		return !time.isBefore(start) || time.isBefore(end);
	}


	private boolean isWorkingDayEnabled(AppSettings settings, DayOfWeek dayOfWeek) {
		return switch (dayOfWeek) {
			case MONDAY -> Boolean.TRUE.equals(settings.getMondayEnabled());
			case TUESDAY -> Boolean.TRUE.equals(settings.getTuesdayEnabled());
			case WEDNESDAY -> Boolean.TRUE.equals(settings.getWednesdayEnabled());
			case THURSDAY -> Boolean.TRUE.equals(settings.getThursdayEnabled());
			case FRIDAY -> Boolean.TRUE.equals(settings.getFridayEnabled());
			case SATURDAY -> Boolean.TRUE.equals(settings.getSaturdayEnabled());
			case SUNDAY -> Boolean.TRUE.equals(settings.getSundayEnabled());
		};
	}


	private SlaValue findSlaValue(Map<Organization, Map<Priority, SlaValue>> slaByPriority, Organization organization, Priority priority) {
		if (organization == null || priority == null) {
			return null;
		}
		Map<Priority, SlaValue> organizationSla = slaByPriority.entrySet().stream()
				.filter(entry -> Objects.equals(entry.getKey().getId(), organization.getId()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
		if (organizationSla == null) {
			return null;
		}
		return organizationSla.entrySet().stream()
				.filter(entry -> Objects.equals(entry.getKey().getId(), priority.getId()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
	}


	private SlaValue findDefaultSlaValue(Map<Organization, Map<Priority, SlaValue>> slaByPriority, Priority priority) {
		if (priority == null) {
			return null;
		}
		Map<Priority, SlaValue> defaultSla = slaByPriority.entrySet().stream()
				.filter(entry -> entry.getKey() instanceof DefaultOrganization)
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
		if (defaultSla == null) {
			return null;
		}
		return defaultSla.entrySet().stream()
				.filter(entry -> Objects.equals(entry.getKey().getId(), priority.getId()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
	}


	private static Map<String, Object> eventPayload(Object... values) {
		if (values.length % 2 != 0) {
			throw new IllegalArgumentException("eventPayload requires key-value pairs");
		}
		Map<String, Object> payload = new HashMap<>();
		for (int i = 0; i < values.length; i += 2) {
			Object key = values[i];
			if (!(key instanceof String)) {
				throw new IllegalArgumentException("eventPayload key must be String");
			}
			payload.put((String) key, values[i + 1]);
		}
		return payload;
	}


	private static void addChange(List<Map<String, Object>> changes, String field, String label, Object oldValue, Object newValue) {
		if (Objects.equals(oldValue, newValue)) {
			return;
		}
		Map<String, Object> change = new LinkedHashMap<>();
		change.put("field", field);
		change.put("label", label);
		change.put("oldValue", Objects.toString(oldValue, ""));
		change.put("newValue", Objects.toString(newValue, ""));
		changes.add(change);
	}


	private static List<Map<String, Object>> buildTaskCreatedChanges(Task task) {
		List<Map<String, Object>> changes = new ArrayList<>();
		if (task == null) {
			return changes;
		}
		addCreatedField(changes, "name", "Название", task.getName());
		addCreatedField(changes, "description", "Описание", task.getDescription());
		addCreatedField(changes, "type", "Тип", getTaskTypeName(task.getType()));
		addCreatedField(changes, "checklist", "Чек-лист", checklistToHistoryValue(task.getChecklist()));
		addCreatedField(changes, "deadline", "Дедлайн", task.getDeadline());
		addCreatedField(changes, "executor", "Исполнитель", getUserDisplayName(task.getExecutor()));
		addCreatedField(changes, "priority", "Приоритет", getName(task.getPriority()));
		addCreatedField(changes, "status", "Статус", getName(task.getStatus()));
		addCreatedField(changes, "tags", "Теги", tagsToHistoryValue(task.getTags()));
		addCreatedField(changes, "linkedMessageId", "Связанное сообщение", task.getLinkedMessageId());
		if (Boolean.TRUE.equals(task.getCompleted())) {
			addCreatedField(changes, "completed", "Закрыта", "Да");
		}
		if (Boolean.TRUE.equals(task.getFrozen())) {
			addCreatedField(changes, "frozen", "Заморозка", "Да");
			addCreatedField(changes, "frozenUntil", "Заморожена до", task.getFrozenUntil());
		}
		return changes;
	}


	private static void addCreatedField(List<Map<String, Object>> changes, String field, String label, Object value) {
		String normalizedValue = Objects.toString(value, "").trim();
		if (normalizedValue.isBlank()) {
			return;
		}
		Map<String, Object> change = new LinkedHashMap<>();
		change.put("field", field);
		change.put("label", label);
		change.put("oldValue", "—");
		change.put("newValue", normalizedValue);
		changes.add(change);
	}


	private static String tagsToHistoryValue(List<Tag> tags) {
		if (tags == null || tags.isEmpty()) {
			return "";
		}
		return tags.stream()
				.filter(Objects::nonNull)
				.map(Tag::getName)
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(name -> !name.isBlank())
				.toList()
				.toString();
	}

	private static String getName(Object object) {
		return switch (object) {
			case null -> "";
			case Status status -> status.getName();
			case Priority priority -> priority.getName();
			case Tag tag -> tag.getName();
			default -> Objects.toString(object, "");
		};
	}


	private static boolean isSameStatus(Status left, Status right) {
		if (left == null || right == null) {
			return false;
		}
		if (left.getId() != null && right.getId() != null) {
			return Objects.equals(left.getId(), right.getId());
		}
		return Objects.equals(left, right)
				|| Objects.equals(getName(left), getName(right));
	}


	private static String getUserDisplayName(User user) {
		if (user == null) {
			return "";
		}
		String lastname = Objects.toString(user.getLastname(), "").trim();
		String firstname = Objects.toString(user.getFirstname(), "").trim();
		String fullName = ("%s %s".formatted(lastname, firstname)).trim();

		if (!fullName.isBlank()) {
			return fullName;
		}
		return Objects.toString(user.getUsername(), "");
	}


	private static String getTaskTypeName(TaskType taskType) {
		if (taskType == null) {
			return "";
		}
		return Objects.toString(taskType.getType(), "");
	}


	private static String checklistToHistoryValue(List<ChecklistItem> checklist) {
		if (checklist == null || checklist.isEmpty()) {
			return "";
		}
		return checklist.stream()
				.filter(item -> item != null && item.getText() != null && !item.getText().isBlank())
				.map(item -> "%s%s".formatted(
						Boolean.TRUE.equals(item.getCompleted()) ? "[x] " : "[ ] ",
						item.getText().trim()
				))
				.toList()
				.toString();
	}


	@Transactional
	public TaskType createTaskType(TaskType taskType) {
		if (taskType == null) {
			throw new IllegalArgumentException("taskType must not be null");
		}
		String type = normalizeTaskTypeName(taskType.getType());
		if (taskTypeRepository.existsByType(type)) {
			throw new IllegalArgumentException("Тип заявки уже существует: %s".formatted(type));
		}
		boolean makeDefault = Boolean.TRUE.equals(taskType.getDefaultSelection()) || !hasDefaultTaskType();
		TaskType savedTaskType = taskTypeRepository.save(TaskType.builder()
				.type(type)
				.orderNumber(getTaskTypes().size() + 1)
				.defaultSelection(makeDefault)
				.checklistTemplate(normalizeChecklist(taskType.getChecklistTemplate()))
				.autoApplyChecklist(!Boolean.FALSE.equals(taskType.getAutoApplyChecklist()))
				.build());
		if (makeDefault) {
			return taskTypeSetDefaultSelection(savedTaskType);
		}
		return savedTaskType;
	}


	@Transactional
	public TaskType updateTaskType(Long id, TaskType request) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		if (request == null) {
			throw new IllegalArgumentException("taskType must not be null");
		}
		TaskType taskType = taskTypeRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Тип заявки не найден: %d".formatted(id)));
		String type = normalizeTaskTypeName(request.getType());
		taskTypeRepository.findByType(type)
				.filter(found -> !found.getId().equals(id))
				.ifPresent(found -> {
					throw new IllegalArgumentException("Тип заявки уже существует: %s".formatted(type));
				});
		taskType.setType(type);
		taskType.setChecklistTemplate(normalizeChecklist(request.getChecklistTemplate()));
		taskType.setAutoApplyChecklist(!Boolean.FALSE.equals(request.getAutoApplyChecklist()));
		TaskType savedTaskType = taskTypeRepository.save(taskType);
		if (Boolean.TRUE.equals(request.getDefaultSelection())) {
			return taskTypeSetDefaultSelection(savedTaskType);
		}
		return savedTaskType;
	}


	@Transactional
	public List<TaskType> resortTaskTypes(List<TaskType> newOrderedTaskTypes) {
		if (newOrderedTaskTypes == null) {
			return getTaskTypes();
		}
		List<TaskType> taskTypes = taskTypeRepository.findAll();
		for (TaskType taskType : taskTypes) {
			int orderNumber = newOrderedTaskTypes.indexOf(taskType);
			if (orderNumber >= 0) {
				taskType.setOrderNumber(orderNumber);
			}
		}
		taskTypeRepository.saveAll(taskTypes);
		return taskTypes.stream().sorted().toList();
	}


	@Transactional
	public void deleteTaskType(Long id) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		TaskType taskType = taskTypeRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Тип заявки не найден: %d".formatted(id)));
		List<Task> tasksWithDeletedType = taskRepository.findAllByTypeId(id);
		for (Task task : tasksWithDeletedType) {
			TaskType oldType = task.getType();
			task.setType(null);
			task.setLastActivity(ZonedDateTime.now());
			Task savedTask = taskRepository.save(task);
			Client client = clientsRepository.findByTaskId(savedTask.getId()).orElse(null);
			if (client != null) {
				globalSearchService.indexClient(client);
				globalSearchService.indexTask(client, savedTask);
			}
			eventPublisher.publish(TriggerType.TASK_UPDATED, eventPayload(
					"task", savedTask,
					"client", client,
					"changes", List.of(Map.of(
							"field", "type",
							"label", "Тип",
							"oldValue", getTaskTypeName(oldType),
							"newValue", ""
					))
			));
		}
		boolean wasDefault = Boolean.TRUE.equals(taskType.getDefaultSelection());
		taskTypeRepository.delete(taskType);
		if (wasDefault) {
			taskTypeRepository.findAll().stream()
					.sorted()
					.findFirst()
					.ifPresent(this::taskTypeSetDefaultSelection);
		}
	}


	@Transactional
	public Task saveTask(Task task) {
		task.setType(resolveTaskType(task.getType()));
		task.setChecklist(normalizeChecklist(task.getChecklist()));
		return taskRepository.save(task);
	}


	public void prepareTaskBeforeSave(Task task) {
		if (task == null) {
			return;
		}
		task.setType(resolveTaskType(task.getType()));
		task.setChecklist(normalizeChecklist(task.getChecklist()));
	}


	TaskType resolveTaskType(TaskType requestType) {
		if (requestType == null) {
			return getDefaultTaskType();
		}
		if (requestType.getId() != null) {
			return taskTypeRepository.findById(requestType.getId())
					.orElseThrow(() -> new IllegalArgumentException("Тип заявки не найден: %d".formatted(requestType.getId())));
		}
		if (requestType.getType() != null && !requestType.getType().isBlank()) {
			String type = normalizeTaskTypeName(requestType.getType());
			return taskTypeRepository.findByType(type)
					.orElseThrow(() -> new IllegalArgumentException("Тип заявки не найден: %s".formatted(type)));
		}
		return getDefaultTaskType();
	}


	private TaskType getDefaultTaskType() {
		return taskTypeRepository.findAll().stream()
				.filter(taskType -> Boolean.TRUE.equals(taskType.getDefaultSelection()))
				.sorted()
				.findFirst()
				.orElseGet(() -> taskTypeRepository.findByType("Запрос")
						.map(this::taskTypeSetDefaultSelection)
						.orElseGet(() -> taskTypeSetDefaultSelection(taskTypeRepository.save(TaskType.builder()
								.type("Запрос")
								.orderNumber(getTaskTypes().size() + 1)
								.defaultSelection(true)
								.autoApplyChecklist(true)
								.checklistTemplate(new ArrayList<>())
								.build()))));
	}


	List<ChecklistItem> normalizeChecklist(List<ChecklistItem> checklist) {
		if (checklist == null) {
			return new ArrayList<>();
		}
		return checklist.stream()
				.filter(item -> item != null && item.getText() != null && !item.getText().isBlank())
				.map(item -> ChecklistItem.builder()
						.id(item.getId() != null && !item.getId().isBlank()
								? item.getId()
								: UUID.randomUUID().toString())
						.text(item.getText().trim())
						.completed(Boolean.TRUE.equals(item.getCompleted()))
						.build())
				.toList();
	}


	private String normalizeTaskTypeName(String type) {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("Название типа заявки не может быть пустым");
		}
		return type.trim();
	}


	private void syncSlaPauseState(Task task) {
		if (task == null || task.getSla() == null || task.getSla().getId() == null) {
			return;
		}
		boolean nowFrozen = Boolean.TRUE.equals(task.getFrozen());
		boolean nowCompleted = Boolean.TRUE.equals(task.getCompleted());
		if (nowFrozen) {
			closeAllSlaPausesExceptReason(task, FREEZE_SLA_PAUSE_REASON);
			if (!hasActiveSlaPauseByReason(task, FREEZE_SLA_PAUSE_REASON)) {
				openSlaPause(
						task,
						FREEZE_SLA_PAUSE_REASON,
						task.getFrozenFrom() != null ? task.getFrozenFrom() : ZonedDateTime.now()
				);
			}
			return;
		}
		if (nowCompleted) {
			closeAllSlaPausesExceptReason(task, CLOSED_SLA_PAUSE_REASON);
			if (!hasActiveSlaPauseByReason(task, CLOSED_SLA_PAUSE_REASON)) {
				openSlaPause(
						task,
						CLOSED_SLA_PAUSE_REASON,
						task.getClosedAt() != null ? task.getClosedAt() : ZonedDateTime.now()
				);
			}
			return;
		}
		closeSlaPause(task, FREEZE_SLA_PAUSE_REASON);
		closeSlaPause(task, CLOSED_SLA_PAUSE_REASON);
	}


	private boolean hasActiveSlaPauseByReason(Task task, String reason) {
		if (task == null || task.getSla() == null || task.getSla().getId() == null) {
			return false;
		}
		return slaPauseRepository
				.findFirstBySlaIdAndReasonAndEndedAtIsNullOrderByStartedAtDesc(task.getSla().getId(), reason)
				.isPresent();
	}


	private void openSlaPause(Task task, String reason, ZonedDateTime startedAt) {
		if (task == null || task.getSla() == null || task.getSla().getId() == null) {
			return;
		}

		slaPauseRepository
				.findFirstBySlaIdAndReasonAndEndedAtIsNullOrderByStartedAtDesc(
						task.getSla().getId(),
						reason
				)
				.ifPresentOrElse(
						pause -> {
							// Уже есть активная пауза с этой причиной.
						},
						() -> {
							SlaPause pause = SlaPause.builder()
									.sla(task.getSla())
									.startedAt(startedAt != null ? startedAt : ZonedDateTime.now())
									.endedAt(null)
									.reason(reason)
									.build();
							slaPauseRepository.save(pause);
						}
				);
	}


	private void closeSlaPause(Task task, String reason) {
		if (task == null || task.getSla() == null || task.getSla().getId() == null) {
			return;
		}
		List<SlaPause> activePauses = slaPauseRepository
				.findAllBySlaIdAndReasonAndEndedAtIsNullOrderByStartedAtDesc(task.getSla().getId(), reason);
		if (activePauses.isEmpty()) {
			return;
		}
		ZonedDateTime now = ZonedDateTime.now();
		activePauses.forEach(pause -> pause.setEndedAt(now));
		slaPauseRepository.saveAll(activePauses);
	}


	@Transactional
	public void autoUnfreezeTask(Long taskId) {
		if (taskId == null) {
			return;
		}
		Task task = taskRepository.findById(taskId).orElse(null);
		if (task == null || !Boolean.TRUE.equals(task.getFrozen())) {
			return;
		}
		Client client = clientsRepository.findByTaskId(task.getId()).orElse(null);
		Status oldStatus = task.getStatus();
		boolean oldFrozen = Boolean.TRUE.equals(task.getFrozen());
		task.setFrozen(false);
		task.setFrozenFrom(null);
		task.setFrozenUntil(null);
		if (task.getPreviousStatus() != null) {
			task.setStatus(task.getPreviousStatus());
		}
		boolean newFrozen = Boolean.TRUE.equals(task.getFrozen());
		List<Map<String, Object>> changes = new ArrayList<>();
		if (oldFrozen != newFrozen) {
			addChange(
					changes,
					"frozen",
					"Заморозка",
					oldFrozen ? "Да" : "Нет",
					newFrozen ? "Да" : "Нет"
			);
			addChange(
					changes,
					"slaPause",
					"SLA-пауза",
					oldFrozen ? "Поставлена на паузу" : "Снята с паузы",
					newFrozen ? "Поставлена на паузу" : "Снята с паузы"
			);
		}
		syncSlaPauseState(task);
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		if (client != null) {
			globalSearchService.indexClient(client);
			globalSearchService.indexTask(client, savedTask);
		}
		if (!changes.isEmpty()) {
			eventPublisher.publish(TriggerType.TASK_UPDATED, eventPayload(
					"task", savedTask,
					"client", client,
					"changes", changes
			));
		}
		if (!isSameStatus(oldStatus, savedTask.getStatus())) {
			eventPublisher.publish(TriggerType.TASK_STATUS_CHANGED, eventPayload(
					"task", savedTask,
					"client", client,
					"oldStatus", oldStatus,
					"newStatus", savedTask.getStatus()
			));
		}
	}


	@Transactional
	public Task pauseTaskSla(Long clientId, Long taskId, String reason) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		Client client = clientsRepository.findById(clientId).orElseThrow();
		Task task = taskRepository.findById(taskId).orElseThrow();
		if (task.getSla() == null || task.getSla().getId() == null) {
			throw new IllegalStateException("У заявки нет SLA");
		}
		if (Boolean.TRUE.equals(task.getFrozen())) {
			throw new IllegalStateException("Нельзя вручную поставить SLA на паузу: заявка уже заморожена");
		}
		if (Boolean.TRUE.equals(task.getCompleted())) {
			throw new IllegalStateException("Нельзя вручную поставить SLA на паузу: заявка закрыта");
		}
		boolean alreadyPaused = slaPauseRepository
				.findFirstBySlaIdAndEndedAtIsNullOrderByStartedAtDesc(task.getSla().getId())
				.isPresent();
		if (alreadyPaused) {
			return task;
		}
		String pauseReason = reason == null || reason.isBlank()
				? MANUAL_SLA_PAUSE_REASON
				: reason.trim();
		SlaPause pause = SlaPause.builder()
				.sla(task.getSla())
				.startedAt(ZonedDateTime.now())
				.endedAt(null)
				.reason(pauseReason)
				.build();
		slaPauseRepository.save(pause);
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		publishManualSlaPauseHistory(client, savedTask, true, pauseReason);
		return savedTask;
	}


	@Transactional
	public Task resumeTaskSla(Long clientId, Long taskId) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		Client client = clientsRepository.findById(clientId).orElseThrow();
		Task task = taskRepository.findById(taskId).orElseThrow();
		if (task.getSla() == null || task.getSla().getId() == null) {
			throw new IllegalStateException("У заявки нет SLA");
		}
		if (Boolean.TRUE.equals(task.getFrozen())) {
			throw new IllegalStateException("Нельзя вручную снять SLA с паузы: заявка заморожена");
		}
		if (Boolean.TRUE.equals(task.getCompleted())) {
			throw new IllegalStateException("Нельзя вручную снять SLA с паузы: заявка закрыта");
		}
		List<SlaPause> activePauses = slaPauseRepository
				.findAllBySlaIdAndEndedAtIsNull(task.getSla().getId());
		if (activePauses.isEmpty()) {
			return task;
		}
		boolean hasAutoNonWorkingTimePause = activePauses.stream()
				.anyMatch(pause -> Objects.equals(
						pause.getReason(),
						AUTO_NON_WORKING_TIME_SLA_PAUSE_REASON
				));

		if (hasAutoNonWorkingTimePause && shouldBlockManualResumeBecauseOfAutoNonWorkingTime()) {
			throw new IllegalStateException("Нельзя вручную снять SLA с авто-паузы: сейчас нерабочее время");
		}
		String reason = activePauses.getFirst().getReason();
		ZonedDateTime now = ZonedDateTime.now();
		activePauses.forEach(pause -> pause.setEndedAt(now));
		slaPauseRepository.saveAll(activePauses);
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		publishManualSlaPauseHistory(client, savedTask, false, reason);
		return savedTask;
	}


	private void publishManualSlaPauseHistory(Client client, Task task, boolean paused, String reason) {
		List<Map<String, Object>> changes = new ArrayList<>();
		addChange(
				changes,
				"slaPause",
				"SLA-пауза",
				paused ? "Снята с паузы" : "Поставлена на паузу",
				paused ? "Поставлена на паузу" : "Снята с паузы"
		);
		if (reason != null && !reason.isBlank()) {
			addChange(
					changes,
					"slaPauseReason",
					"Причина паузы",
					"—",
					reason
			);
		}
		if (!changes.isEmpty()) {
			eventPublisher.publish(TriggerType.TASK_UPDATED, eventPayload(
					"task", task,
					"client", client,
					"changes", changes
			));
		}
		if (client != null) {
			globalSearchService.indexClient(client);
			globalSearchService.indexTask(client, task);
		}
	}


	@Transactional
	public Task pauseTaskSla(Long taskId, String reason) {
		getClientByTaskForCurrentUser(taskId);
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		Task task = taskRepository.findById(taskId)
				.orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + taskId));
		if (task.getSla() == null || task.getSla().getId() == null) {
			throw new IllegalStateException("У заявки нет SLA");
		}
		if (Boolean.TRUE.equals(task.getFrozen())) {
			throw new IllegalStateException("Нельзя вручную поставить SLA на паузу: заявка заморожена");
		}
		if (Boolean.TRUE.equals(task.getCompleted())) {
			throw new IllegalStateException("Нельзя вручную поставить SLA на паузу: заявка закрыта");
		}
		boolean alreadyPaused = slaPauseRepository
				.findFirstBySlaIdAndEndedAtIsNullOrderByStartedAtDesc(task.getSla().getId())
				.isPresent();
		if (alreadyPaused) {
			return task;
		}
		String pauseReason = (reason == null || reason.isBlank())
				? "Ручная пауза SLA"
				: reason.trim();
		SlaPause pause = SlaPause.builder()
				.sla(task.getSla())
				.startedAt(ZonedDateTime.now())
				.endedAt(null)
				.reason(pauseReason)
				.build();
		slaPauseRepository.save(pause);
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		Client client = clientsRepository.findByTaskId(savedTask.getId()).orElse(null);
		publishManualSlaPauseHistory(client, savedTask, true, pauseReason);
		return savedTask;
	}


	@Transactional
	public Task resumeTaskSla(Long taskId) {
		getClientByTaskForCurrentUser(taskId);
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		Task task = taskRepository.findById(taskId)
				.orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + taskId));
		if (task.getSla() == null || task.getSla().getId() == null) {
			throw new IllegalStateException("У заявки нет SLA");
		}
		if (Boolean.TRUE.equals(task.getFrozen())) {
			throw new IllegalStateException("Нельзя вручную снять SLA с паузы: заявка заморожена");
		}
		if (Boolean.TRUE.equals(task.getCompleted())) {
			throw new IllegalStateException("Нельзя вручную снять SLA с паузы: заявка закрыта");
		}
		List<SlaPause> activePauses = slaPauseRepository
				.findAllBySlaIdAndEndedAtIsNull(task.getSla().getId());
		if (activePauses.isEmpty()) {
			return task;
		}
		boolean hasAutoNonWorkingTimePause = activePauses.stream()
				.anyMatch(pause -> Objects.equals(
						pause.getReason(),
						AUTO_NON_WORKING_TIME_SLA_PAUSE_REASON
				));
		if (hasAutoNonWorkingTimePause && shouldBlockManualResumeBecauseOfAutoNonWorkingTime()) {
			throw new IllegalStateException("Нельзя вручную снять SLA с авто-паузы: сейчас нерабочее время");
		}
		String reason = activePauses.getFirst().getReason();
		ZonedDateTime now = ZonedDateTime.now();
		activePauses.forEach(pause -> pause.setEndedAt(now));
		slaPauseRepository.saveAll(activePauses);
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		Client client = clientsRepository.findByTaskId(savedTask.getId()).orElse(null);
		publishManualSlaPauseHistory(client, savedTask, false, reason);
		return savedTask;
	}


	private String normalizeStatusChangeReason(String reason) {
		if (reason == null) {
			return null;
		}
		String normalized = reason.trim();
		return normalized.isBlank() ? null : normalized;
	}


	private void closeAllSlaPausesExceptReason(Task task, String reasonToKeep) {
		if (task == null || task.getSla() == null || task.getSla().getId() == null) {
			return;
		}
		List<SlaPause> activePauses = slaPauseRepository
				.findAllBySlaIdAndEndedAtIsNull(task.getSla().getId());
		List<SlaPause> pausesToClose = activePauses.stream()
				.filter(pause -> !Objects.equals(pause.getReason(), reasonToKeep))
				.toList();
		if (pausesToClose.isEmpty()) {
			return;
		}
		ZonedDateTime now = ZonedDateTime.now();
		pausesToClose.forEach(pause -> pause.setEndedAt(now));
		slaPauseRepository.saveAll(pausesToClose);
	}


	@Transactional
	public TaskType taskTypeSetDefaultSelection(TaskType selectedTaskType) {
		if (selectedTaskType == null || selectedTaskType.getId() == null) {
			throw new IllegalArgumentException("taskType.id must not be null");
		}
		Long selectedId = selectedTaskType.getId();
		TaskType existingTaskType = taskTypeRepository.findById(selectedId)
				.orElseThrow(() -> new IllegalArgumentException("Тип заявки не найден: %d".formatted(selectedId)));
		List<TaskType> taskTypes = taskTypeRepository.findAll().stream()
				.peek(taskType -> taskType.setDefaultSelection(Objects.equals(taskType.getId(), selectedId)))
				.toList();
		taskTypeRepository.saveAll(taskTypes);
		existingTaskType.setDefaultSelection(true);
		return existingTaskType;
	}


	private boolean hasDefaultTaskType() {
		return taskTypeRepository.findAll().stream()
				.anyMatch(taskType -> Boolean.TRUE.equals(taskType.getDefaultSelection()));
	}


	private Client getClientForCurrentUser(Long clientId) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		Client client = clientsRepository.findById(clientId).orElseThrow();
		userService.assertCurrentUserCanAccessClient(client);
		return client;
	}


	private Client getClientByTaskForCurrentUser(Long taskId) {
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		Client client = clientsRepository.findByTaskId(taskId).orElseThrow();
		userService.assertCurrentUserCanAccessClient(client);
		return client;
	}


	private Task createTaskForClient(Client client, Task task) {
		prepareTaskBeforeSave(task);
		validateExecutorIsPresentWhenClosing(task, task.getStatus(), CompletedStatus.getInstance());
		setSla(client, task);
		if (task.getMessages() != null && !task.getMessages().isEmpty()) {
			messageRepository.saveAll(task.getMessages());
		}
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		if (client.getTasks() == null) {
			client.setTasks(new ArrayList<>());
		}
		client.getTasks().add(savedTask);
		clientsRepository.save(client);
		globalSearchService.indexClient(client);
		globalSearchService.indexTask(client, savedTask);
		eventPublisher.publish(TriggerType.TASK_CREATED, eventPayload(
				"task", savedTask,
				"client", client,
				"changes", buildTaskCreatedChanges(savedTask)
		));
		if (savedTask.getExecutor() != null) {
			userNotificationService.send(new UserNotification(
					UserNotificationEvent.NEW_TASK,
					savedTask.getName(),
					savedTask.getExecutor().getId()
			));
		}
		return savedTask;
	}


	private static void validateExecutorIsPresentWhenClosing(Task task, Status requestedStatus, Status completedStatus) {
		if (task == null) {
			return;
		}
		boolean closing = Boolean.TRUE.equals(task.getCompleted()) || isSameStatus(requestedStatus, completedStatus);
		if (!closing) {
			return;
		}
		if (task.getExecutor() == null || task.getExecutor().getId() == null) {
			throw new IllegalArgumentException("Перед закрытием заявки укажите исполнителя");
		}
	}

}
