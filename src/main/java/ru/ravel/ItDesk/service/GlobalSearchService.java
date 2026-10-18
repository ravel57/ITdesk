package ru.ravel.ItDesk.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.GlobalSearchDocument;
import ru.ravel.ItDesk.dto.GlobalSearchResultDto;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;


@Service
@RequiredArgsConstructor
public class GlobalSearchService {

	private final ElasticsearchClient elasticsearchClient;
	private final ClientRepository clientRepository;
	private final TaskRepository taskRepository;
	private final UserRepository userRepository;
	private final KnowledgeService knowledgeService;
	private final MessageRepository messageRepository;
	private final ServiceRepository serviceRepository;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private static final DateTimeFormatter INDEX_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
	private static final String ALREADY_EXISTS = "resource_already_exists_exception";

	/**
	 * Имя алиаса: поиск и запись идут через него, а данные лежат в индексах «{alias}_{время}».
	 * Переиндексация собирает новый индекс рядом и атомарно переключает на него алиас, так что пустого окна нет.
	 */
	@Value("${elasticsearch.index:uldesk_global_search}")
	private String indexName;

	/** Создание индекса и переключение алиаса не должны пересекаться, иначе алиас окажется на двух индексах. */
	private final ReentrantLock indexLock = new ReentrantLock();
	private final AtomicBoolean reindexing = new AtomicBoolean();
	/** Документы, изменённые во время переиндексации: новый индекс собран из более раннего снимка БД. Пустое значение — удаление. */
	private final Map<String, Optional<GlobalSearchDocument>> changedDuringReindex = new ConcurrentHashMap<>();


	public List<GlobalSearchResultDto> search(String query, Set<String> entityTypes) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		String normalizedQuery = normalizeSearchQuery(query);
		if (normalizedQuery.isBlank()) {
			return List.of();
		}
		Set<String> allowedEntityTypes = normalizeEntityTypes(entityTypes);
		try {
			ensureIndexExists();
			boolean fuzzyAllowed = isFuzzySearchAllowed(normalizedQuery);

			SearchResponse<GlobalSearchDocument> response = elasticsearchClient.search(request -> request
							.index(indexName)
							.size(200)
							.query(q -> q
									.bool(b -> {
										b.should(s -> s
												.matchPhrase(m -> m
														.field("text")
														.query(normalizedQuery)
														.boost(80.0f)
												)
										);

										b.should(s -> s
												.matchPhrase(m -> m
														.field("title")
														.query(normalizedQuery)
														.boost(8.0f)
												)
										);

										b.should(s -> s
												.multiMatch(m -> m
														.query(normalizedQuery)
														.type(TextQueryType.BestFields)
														.fields(
																"text^10",
																"title^4"
														)
												)
										);

										b.should(s -> s
												.multiMatch(m -> m
														.query(query)
														.type(TextQueryType.BestFields)
														.fields(
																"text^10",
																"title^4"
														)
												)
										);

										if (fuzzyAllowed) {
											b.should(s -> s
													.multiMatch(m -> m
															.query(normalizedQuery)
															.type(TextQueryType.BestFields)
															.fields(
																	"text^2",
																	"title^2",
																	"subtitle"
															)
															.fuzziness("AUTO")
															.boost(0.3f)
													)
											);
										}

										return b.minimumShouldMatch("1");
									})
							),
					GlobalSearchDocument.class
			);
			return response.hits().hits().stream()
					.filter(hit -> hit.source() != null)
					.map(hit -> toResult(hit.source(), hit.score()))
					.filter(result -> allowedEntityTypes.isEmpty() || allowedEntityTypes.contains(result.getEntityType()))
					.sorted(Comparator
							.comparing(
									(GlobalSearchResultDto result) -> Objects.requireNonNullElse(result.getScore(), 0.0),
									Comparator.reverseOrder()
							)
							.thenComparingInt(result -> getEntityTypePriority(result.getEntityType()))
					)
					.limit(50)
					.toList();
		} catch (IOException | ElasticsearchException e) {
			throw new IllegalStateException("Global search failed", e);
		}
	}


	@Transactional(readOnly = true)
	public Map<String, Object> reindexAll() {
		if (!reindexing.compareAndSet(false, true)) {
			logger.info("Global reindex skipped: already running");
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("index", indexName);
			result.put("skipped", "already running");
			return result;
		}
		changedDuringReindex.clear();
		String newIndex = indexName + "_" + LocalDateTime.now().format(INDEX_SUFFIX);
		try {
			createIndex(newIndex, false);

			Map<String, GlobalSearchDocument> documents = new LinkedHashMap<>();

			clientRepository.findAll().forEach(client -> {
				putDocument(documents, toClientDocument(client));

				safeList(client.getTasks()).forEach(task -> {
					putDocument(documents, toTaskDocument(client, task));

					safeList(task.getMessages()).forEach(message -> {
						if (!Boolean.TRUE.equals(message.getDeleted())) {
							putDocument(documents, toTaskMessageDocument(client, task, message));
						}
					});
				});
			});

			messageRepository.findClientMessagesForGlobalSearch().forEach(row -> {
				Client client = row.getClient();
				Message message = row.getMessage();

				if (client == null || client.getId() == null || message == null || message.getId() == null) {
					return;
				}

				if (!Boolean.TRUE.equals(message.getDeleted())) {
					putDocument(documents, toClientMessageDocument(client, message));
				}
			});

			taskRepository.findAll().forEach(task -> {
				putDocument(documents, toTaskDocument(null, task));
			});

			userRepository.findAll().stream()
					.filter(this::isSearchableUser)
					.forEach(user -> putDocument(documents, toUserDocument(user)));

			safeList(knowledgeService.getKnowledgeBase()).forEach(knowledge -> {
				if (knowledge != null && knowledge.getId() != null) {
					putDocument(documents, toKnowledgeDocument(knowledge));
				}
			});

			serviceRepository.findAll().forEach(service -> putDocument(documents, toServiceDocument(service)));
			BulkRequest.Builder bulk = new BulkRequest.Builder();
			for (GlobalSearchDocument document : documents.values()) {
				if (document == null || document.getId() == null) {
					continue;
				}
				bulk.operations(operation -> operation
						.index(index -> index
								.index(newIndex)
								.id(document.getId())
								.document(document)
						)
				);
			}
			List<String> errors = new ArrayList<>();
			if (!documents.isEmpty()) {
				BulkResponse bulkResponse = elasticsearchClient.bulk(bulk.refresh(Refresh.True).build());
				if (bulkResponse.errors()) {
					bulkResponse.items().stream()
							.filter(item -> item.error() != null)
							.limit(10)
							.forEach(item -> errors.add("%s: %s".formatted(
									item.id(),
									item.error().reason()
							)));
				}
			}
			long count = elasticsearchClient.count(request -> request.index(newIndex)).count();
			switchAlias(newIndex);
			replayChangedDuringReindex();
			deleteStaleIndices(newIndex);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("index", indexName);
			result.put("physicalIndex", newIndex);
			result.put("prepared", documents.size());
			result.put("count", count);
			result.put("bulkErrors", errors);
			return result;
		} catch (IOException | ElasticsearchException e) {
			deleteIndexQuietly(newIndex);
			throw new IllegalStateException("Global reindex failed", e);
		} finally {
			changedDuringReindex.clear();
			reindexing.set(false);
		}
	}

	public void indexClient(Client client) {
		if (client == null || client.getId() == null) {
			return;
		}
		indexDocument(toClientDocument(client));
	}


	public void indexTask(Client client, Task task) {
		if (task == null || task.getId() == null) {
			return;
		}
		indexDocument(toTaskDocument(client, task));
	}


	public void indexClientMessage(Client client, Message message) {
		if (client == null || client.getId() == null || message == null || message.getId() == null) {
			return;
		}
		if (Boolean.TRUE.equals(message.getDeleted())) {
			deleteDocument("CLIENT_MESSAGE:" + message.getId());
			return;
		}
		indexDocument(toClientMessageDocument(client, message));
	}


	public void indexTaskMessage(Client client, Task task, Message message) {
		if (task == null || task.getId() == null || message == null || message.getId() == null) {
			return;
		}
		if (Boolean.TRUE.equals(message.getDeleted())) {
			deleteDocument("TASK_MESSAGE:" + message.getId());
			return;
		}
		indexDocument(toTaskMessageDocument(client, task, message));
	}


	public void indexKnowledge(Knowledge knowledge) {
		if (knowledge == null || knowledge.getId() == null) {
			return;
		}
		indexDocument(toKnowledgeDocument(knowledge));
	}


	public void indexService(ru.ravel.ItDesk.model.Service service) {
		if (service == null || service.getId() == null) {
			return;
		}
		indexDocument(toServiceDocument(service));
	}


	public void deleteService(Long serviceId) {
		if (serviceId != null) {
			deleteDocument("SERVICE:" + serviceId);
		}
	}


	public void indexUser(User user) {
		if (user == null || user.getId() == null) {
			return;
		}

		if (!isSearchableUser(user)) {
			deleteUser(user.getId());
			return;
		}
		indexDocument(toUserDocument(user));
	}


	public void deleteUser(Long userId) {
		if (userId == null) {
			return;
		}
		deleteDocument("USER:" + userId);
	}


	private boolean isSearchableUser(User user) {
		return user != null
				&& user.getId() != null
				&& !Objects.equals(user, SystemUser.getInstance())
				&& user.isEnabled();
	}


	public void deleteKnowledge(Long knowledgeId) {
		if (knowledgeId == null) {
			return;
		}
		deleteDocument("KNOWLEDGE:" + knowledgeId);
	}


	public void deleteDocument(String id) {
		if (id == null || id.isBlank()) {
			return;
		}
		rememberDuringReindex(id, null);
		try {
			ensureIndexExists();
			elasticsearchClient.delete(request -> request
					.index(indexName)
					.id(id)
					.refresh(Refresh.True)
			);
		} catch (Exception ignored) {
		}
	}


	private void indexDocument(GlobalSearchDocument document) {
		if (document == null || document.getId() == null) {
			return;
		}
		rememberDuringReindex(document.getId(), document);
		try {
			ensureIndexExists();
			elasticsearchClient.index(request -> request
					.index(indexName)
					.id(document.getId())
					.document(document)
					.refresh(Refresh.True)
			);
		} catch (Exception e) {
			logger.error("Failed to index global search document: id={}, type={}", document.getId(), document.getEntityType(), e);
		}
	}


	private void rememberDuringReindex(String id, GlobalSearchDocument document) {
		if (reindexing.get()) {
			changedDuringReindex.put(id, Optional.ofNullable(document));
		}
	}


	/** Алиас (или старый индекс с тем же именем) есть — ничего не делаем; иначе создаём первый индекс под алиасом. */
	private void ensureIndexExists() throws IOException {
		if (exists(indexName)) {
			return;
		}
		indexLock.lock();
		try {
			if (exists(indexName)) {
				return;
			}
			createIndex(indexName + "_" + LocalDateTime.now().format(INDEX_SUFFIX), true);
		} finally {
			indexLock.unlock();
		}
	}


	private boolean exists(String name) throws IOException {
		return elasticsearchClient.indices().exists(ExistsRequest.of(request -> request.index(name))).value();
	}


	/** Создаёт индекс с нашей схемой; если его успели создать параллельно, это не ошибка. */
	private void createIndex(String name, boolean withAlias) throws IOException {
		try {
			elasticsearchClient.indices().create(CreateIndexRequest.of(request -> {
				request.index(name);
				if (withAlias) {
					request.aliases(indexName, alias -> alias);
				}
				return request.mappings(mapping -> mapping
						.properties("title", property -> property.text(text -> text))
						.properties("subtitle", property -> property.text(text -> text))
						.properties("text", property -> property.text(text -> text))
						.properties("entityType", property -> property.keyword(keyword -> keyword))
						.properties("entityId", property -> property.long_(longNumber -> longNumber))
						.properties("clientId", property -> property.long_(longNumber -> longNumber))
						.properties("taskId", property -> property.long_(longNumber -> longNumber))
						.properties("url", property -> property.keyword(keyword -> keyword))
						.properties("createdAt", property -> property.date(date -> date))
						.properties("updatedAt", property -> property.date(date -> date))
				);
			}));
		} catch (ElasticsearchException e) {
			if (!isAlreadyExists(e)) {
				throw e;
			}
		}
	}


	private static boolean isAlreadyExists(ElasticsearchException e) {
		return e.error() != null && ALREADY_EXISTS.equals(e.error().type());
	}


	/**
	 * Одним атомарным запросом вешает алиас на новый индекс и снимает его со старых.
	 * Старый индекс с именем алиаса (до перехода на алиасы) удаляется тем же запросом.
	 */
	private void switchAlias(String newIndex) throws IOException {
		indexLock.lock();
		try {
			List<String> previous = aliasTargets();
			boolean legacyIndex = previous.isEmpty() && exists(indexName);
			elasticsearchClient.indices().updateAliases(request -> {
				request.actions(action -> action.add(add -> add.index(newIndex).alias(indexName)));
				previous.forEach(old -> request.actions(action -> action.remove(remove -> remove.index(old).alias(indexName))));
				if (legacyIndex) {
					request.actions(action -> action.removeIndex(remove -> remove.index(indexName)));
				}
				return request;
			});
		} finally {
			indexLock.unlock();
		}
	}


	private List<String> aliasTargets() throws IOException {
		if (!elasticsearchClient.indices().existsAlias(request -> request.name(indexName)).value()) {
			return List.of();
		}
		return new ArrayList<>(elasticsearchClient.indices().getAlias(request -> request.name(indexName)).result().keySet());
	}


	/** Переносит в новый индекс то, что поменялось, пока он собирался (эти записи ушли в старый индекс). */
	private void replayChangedDuringReindex() {
		if (changedDuringReindex.isEmpty()) {
			return;
		}
		changedDuringReindex.forEach((id, document) -> {
			try {
				if (document.isPresent()) {
					elasticsearchClient.index(request -> request.index(indexName).id(id).document(document.get()));
				} else {
					elasticsearchClient.delete(request -> request.index(indexName).id(id));
				}
			} catch (Exception e) {
				logger.warn("Failed to replay global search change after reindex: id={}", id, e);
			}
		});
		try {
			elasticsearchClient.indices().refresh(request -> request.index(indexName));
		} catch (Exception e) {
			logger.warn("Failed to refresh global search index after reindex", e);
		}
	}


	/** Удаляет все наши индексы, кроме текущего: прошлые версии и остатки прерванных переиндексаций. */
	private void deleteStaleIndices(String keep) {
		try {
			elasticsearchClient.indices().get(request -> request.index(indexName + "_*")).result().keySet().stream()
					.filter(name -> !name.equals(keep))
					.forEach(this::deleteIndexQuietly);
		} catch (Exception e) {
			logger.warn("Failed to clean up old global search indices", e);
		}
	}


	private void deleteIndexQuietly(String name) {
		try {
			elasticsearchClient.indices().delete(request -> request.index(name).ignoreUnavailable(true));
		} catch (Exception e) {
			logger.warn("Failed to delete global search index {}", name, e);
		}
	}


	private GlobalSearchDocument toClientDocument(Client client) {
		String fullName = getClientName(client);
		return GlobalSearchDocument.builder()
				.id("CLIENT:" + client.getId())
				.entityType("CLIENT")
				.entityId(client.getId())
				.clientId(client.getId())
				.title(fullName)
				.subtitle("Клиент")
				.text(String.join(" ",
						nullToEmpty(client.getFirstname()),
						nullToEmpty(client.getLastname()),
						nullToEmpty(client.getEmail()),
						nullToEmpty(client.getPhoneNumber()),
						nullToEmpty(client.getWhatsappRecipient()),
						nullToEmpty(client.getMoreInfo())
				))
				.url("/chats/%d".formatted(client.getId()))
				.createdAt(null)
				.updatedAt(null)
				.meta(Map.of(
						"email", nullToEmpty(client.getEmail()),
						"phone", nullToEmpty(client.getPhoneNumber())
				))
				.build();
	}


	private GlobalSearchDocument toTaskDocument(Client client, Task task) {
		String taskNumber = String.valueOf(task.getId());
		String taskTitle = nullToEmpty(task.getName()).trim();
		return GlobalSearchDocument.builder()
				.id("TASK:" + task.getId())
				.entityType("TASK")
				.entityId(task.getId())
				.clientId(client == null ? null : client.getId())
				.taskId(task.getId())
				.title(taskTitle.isBlank()
						? "Заявка №%s".formatted(taskNumber)
						: "Заявка №%s · %s".formatted(taskNumber, taskTitle))
				.subtitle("Заявка")
				.text(String.join(" ",
						"заявка",
						"№" + taskNumber,
						"#" + taskNumber,
						"id" + taskNumber,
						"task" + taskNumber,
						"task-" + taskNumber,
						"номер " + taskNumber,
						taskNumber,
						nullToEmpty(task.getDescription()),
						task.getStatus() == null ? "" : nullToEmpty(task.getStatus().getName()),
						task.getPriority() == null ? "" : nullToEmpty(task.getPriority().getName()),
						task.getService() == null ? "" : nullToEmpty(task.getService().getCode()),
						task.getService() == null ? "" : nullToEmpty(task.getService().getName()),
						task.getExecutor() == null ? "" : getUserName(task.getExecutor())
				))
				.url(client == null
						? "/tasks?task=%d".formatted(task.getId())
						: "/chats/%d?task=%d".formatted(client.getId(), task.getId()))
				.createdAt(task.getCreatedAt())
				.updatedAt(task.getLastActivity())
				.meta(Map.of(
						"completed", Boolean.TRUE.equals(task.getCompleted()),
						"status", task.getStatus() == null ? "" : nullToEmpty(task.getStatus().getName()),
						"priority", task.getPriority() == null ? "" : nullToEmpty(task.getPriority().getName()),
						"service", task.getService() == null ? "" : nullToEmpty(task.getService().getName()),
						"serviceCode", task.getService() == null ? "" : nullToEmpty(task.getService().getCode()),
						"number", taskNumber
				))
				.build();
	}


	private GlobalSearchDocument toServiceDocument(ru.ravel.ItDesk.model.Service service) {
		String code = nullToEmpty(service.getCode()).trim();
		String name = nullToEmpty(service.getName()).trim();
		String criticality = service.getCriticality() == null ? "" : nullToEmpty(service.getCriticality().getName());
		String owner = service.getOwner() == null ? "" : getUserName(service.getOwner());
		String supportLine = service.getDefaultSupportLine() == null ? "" : nullToEmpty(service.getDefaultSupportLine().getName());
		return GlobalSearchDocument.builder()
				.id("SERVICE:" + service.getId())
				.entityType("SERVICE")
				.entityId(service.getId())
				.title(code.isBlank() ? name : code + " · " + name)
				.subtitle("Сервис")
				.text(String.join(" ", "сервис", code, name, nullToEmpty(service.getDescription()), criticality, owner, supportLine))
				.url("/settings/services?serviceId=%d".formatted(service.getId()))
				.createdAt(null)
				.updatedAt(service.getLastMonitoringAt())
				.meta(Map.of(
						"code", code,
						"active", !Boolean.FALSE.equals(service.getActive()),
						"criticality", criticality,
						"monitoringStatus", Objects.toString(service.getMonitoringStatus(), "")
				))
				.build();
	}


	private GlobalSearchDocument toClientMessageDocument(Client client, Message message) {
		return GlobalSearchDocument.builder()
				.id("CLIENT_MESSAGE:" + message.getId())
				.entityType("CLIENT_MESSAGE")
				.entityId(message.getId())
				.clientId(client.getId())
				.title(buildMessageTitle(
						message,
						Boolean.TRUE.equals(message.getIsSent()) ? "Исходящее сообщение" : "Входящее сообщение"
				))
				.subtitle(Boolean.TRUE.equals(message.getIsSent()) ? "Исходящее сообщение" : "Входящее сообщение")
				.text(nullToEmpty(message.getText()))
				.url("/chats/%d?messageId=%d".formatted(client.getId(), message.getId()))
				.createdAt(message.getDate())
				.updatedAt(message.getDate())
				.meta(Map.of(
						"isSent", Boolean.TRUE.equals(message.getIsSent()),
						"isComment", Boolean.TRUE.equals(message.getIsComment())
				))
				.build();
	}


	private GlobalSearchDocument toTaskMessageDocument(Client client, Task task, Message message) {
		return GlobalSearchDocument.builder()
				.id("TASK_MESSAGE:" + message.getId())
				.entityType("TASK_MESSAGE")
				.entityId(message.getId())
				.clientId(client == null ? null : client.getId())
				.taskId(task.getId())
				.title(buildMessageTitle(message, "Сообщение в заявке №" + task.getId()))
				.subtitle("Комментарий / сообщение в заявке")
				.text(nullToEmpty(message.getText()))
				.url(client == null
						? "/tasks?task=%d&messageId=%d".formatted(task.getId(), message.getId())
						: "/chats/%d?task=%d&messageId=%d".formatted(client.getId(), task.getId(), message.getId()))
				.createdAt(message.getDate())
				.updatedAt(message.getDate())
				.meta(Map.of(
						"isSent", Boolean.TRUE.equals(message.getIsSent()),
						"isComment", Boolean.TRUE.equals(message.getIsComment())
				))
				.build();
	}


	private GlobalSearchDocument toUserDocument(User user) {
		return GlobalSearchDocument.builder()
				.id("USER:" + user.getId())
				.entityType("USER")
				.entityId(user.getId())
				.title(getUserName(user))
				.subtitle("Оператор")
				.text(String.join(" ",
						getUserName(user),
						nullToEmpty(user.getUsername())
				))
				.url("/settings/users")
				.createdAt(null)
				.updatedAt(null)
				.meta(Map.of(
						"username", nullToEmpty(user.getUsername())
				))
				.build();
	}


	private GlobalSearchDocument toKnowledgeDocument(Knowledge knowledge) {
		String title = nullToEmpty(knowledge.getTitle()).trim();
		String text = safeList(knowledge.getTexts()).stream()
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(value -> !value.isBlank())
				.reduce("", (left, right) -> left.isBlank() ? right : left + " " + right);
		String tags = safeList(knowledge.getTags()).stream()
				.filter(Objects::nonNull)
				.map(Tag::getName)
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(value -> !value.isBlank())
				.reduce("", (left, right) -> left.isBlank() ? right : left + " " + right);
		return GlobalSearchDocument.builder()
				.id("KNOWLEDGE:" + knowledge.getId())
				.entityType("KNOWLEDGE")
				.entityId(knowledge.getId())
				.title(title.isBlank() ? "Статья БЗ №%d".formatted(knowledge.getId()) : title)
				.subtitle("База знаний")
				.text(String.join(" ",
						"база знаний",
						"бз",
						"статья",
						title,
						text,
						tags
				))
				.url("/knowledge-base?knowledgeId=%d".formatted(knowledge.getId()))
				.createdAt(null)
				.updatedAt(null)
				.meta(Map.of(
						"tags", tags
				))
				.build();
	}



	private GlobalSearchResultDto toResult(GlobalSearchDocument document, Double score) {
		return GlobalSearchResultDto.builder()
				.id(document.getId())
				.entityType(document.getEntityType())
				.entityId(document.getEntityId())
				.clientId(document.getClientId())
				.taskId(document.getTaskId())
				.title(document.getTitle())
				.text(getResultDisplayText(document))
				.subtitle(document.getSubtitle())
				.url(document.getUrl())
				.score(score)
				.meta(document.getMeta())
				.build();
	}


	private String getResultDisplayText(GlobalSearchDocument document) {
		if (document == null || document.getEntityType() == null) {
			return "";
		}
		if ("TASK".equals(document.getEntityType())) {
			return "";
		}
		return nullToEmpty(document.getText()).trim();
	}


	private String getClientName(Client client) {
		String name = String.join(" ",
				nullToEmpty(client.getLastname()),
				nullToEmpty(client.getFirstname())
		).trim();
		if (!name.isBlank()) {
			return name;
		}
		if (client.getEmail() != null && !client.getEmail().isBlank()) {
			return client.getEmail();
		}
		if (client.getPhoneNumber() != null && !client.getPhoneNumber().isBlank()) {
			return client.getPhoneNumber();
		}
		return "Клиент %d".formatted(client.getId());
	}


	private String getUserName(User user) {
		String name = String.join(" ",
				nullToEmpty(user.getLastname()),
				nullToEmpty(user.getFirstname())
		).trim();
		if (!name.isBlank()) {
			return name;
		}
		String username = nullToEmpty(user.getUsername()).trim();
		if (!username.isBlank()) {
			return username;
		}
		return "Пользователь %d".formatted(user.getId());
	}


	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}


	private <T> List<T> safeList(List<T> list) {
		return list == null ? List.of() : list;
	}


	private void putDocument(Map<String, GlobalSearchDocument> documents, GlobalSearchDocument document) {
		if (document == null || document.getId() == null) {
			return;
		}
		documents.put(document.getId(), document);
	}


	public Map<String, Object> countDocuments() {
		try {
			ensureIndexExists();
			long count = elasticsearchClient.count(request -> request
					.index(indexName)
			).count();
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("index", indexName);
			result.put("count", count);
			return result;
		} catch (IOException | ElasticsearchException e) {
			throw new IllegalStateException("Global search count failed", e);
		}
	}


	private boolean isFuzzySearchAllowed(String query) {
		if (query == null || query.isBlank()) {
			return false;
		}
		String normalized = query.trim();
		if (normalized.matches(".*\\d.*")) {
			return false;
		}
		return normalized.length() >= 4;
	}


	private String normalizeSearchQuery(String query) {
		if (query == null) {
			return "";
		}
		String normalized = query
				.trim()
				.replaceAll("[^\\p{L}\\p{N}@._-]+", " ")
				.replaceAll("\\s+", " ")
				.trim();
		return normalized.isBlank() ? query.trim() : normalized;
	}


	private Set<String> normalizeEntityTypes(Set<String> entityTypes) {
		if (entityTypes == null || entityTypes.isEmpty()) {
			return Set.of();
		}
		Set<String> allowed = Set.of(
				"USER",
				"CLIENT",
				"TASK",
				"SERVICE",
				"KNOWLEDGE",
				"CLIENT_MESSAGE",
				"TASK_MESSAGE"
		);
		Set<String> result = new HashSet<>();
		entityTypes.forEach(type -> {
			if (type == null) {
				return;
			}
			String normalized = type.trim().toUpperCase(Locale.ROOT);
			if (allowed.contains(normalized)) {
				result.add(normalized);
			}
		});
		return result;
	}


	private int getEntityTypePriority(String entityType) {
		if (entityType == null) {
			return 100;
		}
		return switch (entityType) {
			case "USER" -> 0;
			case "CLIENT" -> 1;
			case "TASK" -> 2;
			case "SERVICE" -> 3;
			case "KNOWLEDGE" -> 4;
			case "CLIENT_MESSAGE" -> 5;
			case "TASK_MESSAGE" -> 6;
			default -> 100;
		};
	}


	private String buildMessageTitle(Message message, String fallback) {
		String text = message == null ? "" : nullToEmpty(message.getText()).trim();
		if (text.isBlank()) {
			return fallback;
		}
		String normalized = text.replaceAll("\\s+", " ");
		if (normalized.length() <= 80) {
			return normalized;
		}
		return normalized.substring(0, 80) + "...";
	}

}