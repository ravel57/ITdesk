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
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.TaskRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


@Service
@RequiredArgsConstructor
public class GlobalSearchService {

	private final ElasticsearchClient elasticsearchClient;
	private final ClientRepository clientRepository;
	private final TaskRepository taskRepository;
	private final UserRepository userRepository;
	private final KnowledgeService knowledgeService;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Value("${elasticsearch.index:uldesk_global_search}")
	private String indexName;


	public List<GlobalSearchResultDto> search(String query) {
		return search(query, Set.of());
	}


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
			SearchResponse<GlobalSearchDocument> response = elasticsearchClient.search(request -> request
							.index(indexName)
							.size(200)
							.query(q -> q
									.bool(b -> b
											.should(s -> s
													.multiMatch(m -> m
															.query(query)
															.type(TextQueryType.BestFields)
															.fields(
																	"title^5",
																	"subtitle^2",
																	"text",
																	"entityType"
															)
													)
											)
											.should(s -> s
													.multiMatch(m -> m
															.query(normalizedQuery)
															.type(TextQueryType.BestFields)
															.fields(
																	"title^5",
																	"subtitle^2",
																	"text",
																	"entityType"
															)
															.fuzziness("AUTO")
													)
											)
											.minimumShouldMatch("1")
									)
							),
					GlobalSearchDocument.class
			);
			return response.hits().hits().stream()
					.filter(hit -> hit.source() != null)
					.map(hit -> toResult(hit.source(), hit.score()))
					.filter(result -> allowedEntityTypes.isEmpty() || allowedEntityTypes.contains(result.getEntityType()))
					.sorted(Comparator
							.comparingInt((GlobalSearchResultDto result) -> getEntityTypePriority(result.getEntityType()))
							.thenComparing(
									result -> Objects.requireNonNullElse(result.getScore(), 0.0),
									Comparator.reverseOrder()
							)
					)
					.limit(50)
					.toList();
		} catch (IOException | ElasticsearchException e) {
			throw new IllegalStateException("Global search failed", e);
		}
	}


	@Transactional(readOnly = true)
	public Map<String, Object> reindexAll() {
		try {
			recreateIndex();
			List<GlobalSearchDocument> documents = new ArrayList<>();
			clientRepository.findAll().forEach(client -> {
				documents.add(toClientDocument(client));
				safeList(client.getMessages()).forEach(message -> {
					if (!Boolean.TRUE.equals(message.getDeleted())) {
						documents.add(toClientMessageDocument(client, message));
					}
				});
				safeList(client.getTasks()).forEach(task -> {
					documents.add(toTaskDocument(client, task));
					safeList(task.getMessages()).forEach(message -> {
						if (!Boolean.TRUE.equals(message.getDeleted())) {
							documents.add(toTaskMessageDocument(client, task, message));
						}
					});
				});
			});
			taskRepository.findAll().stream()
					.filter(task -> documents.stream().noneMatch(doc -> doc.getId().equals("TASK:" + task.getId())))
					.forEach(task -> documents.add(toTaskDocument(null, task)));
			userRepository.findAll().forEach(user -> documents.add(toUserDocument(user)));
			safeList(knowledgeService.getKnowledgeBase()).forEach(knowledge -> {
				if (knowledge != null && knowledge.getId() != null) {
					documents.add(toKnowledgeDocument(knowledge));
				}
			});
			BulkRequest.Builder bulk = new BulkRequest.Builder();
			for (GlobalSearchDocument document : documents) {
				if (document == null || document.getId() == null) {
					continue;
				}
				bulk.operations(operation -> operation
						.index(index -> index
								.index(indexName)
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
			long count = elasticsearchClient.count(request -> request.index(indexName)).count();
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("index", indexName);
			result.put("prepared", documents.size());
			result.put("count", count);
			result.put("bulkErrors", errors);
			return result;
		} catch (IOException | ElasticsearchException e) {
			throw new IllegalStateException("Global reindex failed", e);
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
		indexDocument(toClientMessageDocument(client, message));
	}


	public void indexTaskMessage(Client client, Task task, Message message) {
		if (task == null || task.getId() == null || message == null || message.getId() == null) {
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


	private void ensureIndexExists() throws IOException {
		boolean exists = elasticsearchClient.indices()
				.exists(ExistsRequest.of(request -> request.index(indexName)))
				.value();
		if (exists) {
			return;
		}
		elasticsearchClient.indices().create(CreateIndexRequest.of(request -> request
				.index(indexName)
				.mappings(mapping -> mapping
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
				)
		));
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
						nullToEmpty(task.getName()),
						nullToEmpty(task.getDescription()),
						task.getStatus() == null ? "" : nullToEmpty(task.getStatus().getName()),
						task.getPriority() == null ? "" : nullToEmpty(task.getPriority().getName()),
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
						"number", taskNumber
				))
				.build();
	}


	private GlobalSearchDocument toClientMessageDocument(Client client, Message message) {
		return GlobalSearchDocument.builder()
				.id("CLIENT_MESSAGE:" + message.getId())
				.entityType("CLIENT_MESSAGE")
				.entityId(message.getId())
				.clientId(client.getId())
				.title(getClientName(client))
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
				.title(nullToEmpty(task.getName()))
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
				.text(document.getText())
				.subtitle(document.getSubtitle())
				.url(document.getUrl())
				.score(score)
				.meta(document.getMeta())
				.build();
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
		return "Клиент " + client.getId();
	}


	private String getUserName(User user) {
		String name = String.join(" ",
				nullToEmpty(user.getLastname()),
				nullToEmpty(user.getFirstname())
		).trim();
		if (!name.isBlank()) {
			return name;
		}
		if (!user.getUsername().isBlank()) {
			return user.getUsername();
		}
		return "Пользователь " + user.getId();
	}


	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}


	private <T> List<T> safeList(List<T> list) {
		return list == null ? List.of() : list;
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


	private void recreateIndex() throws IOException {
		boolean exists = elasticsearchClient.indices()
				.exists(ExistsRequest.of(request -> request.index(indexName)))
				.value();
		if (exists) {
			elasticsearchClient.indices().delete(request -> request.index(indexName));
		}
		ensureIndexExists();
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
			case "KNOWLEDGE" -> 3;
			case "CLIENT_MESSAGE" -> 4;
			case "TASK_MESSAGE" -> 5;
			default -> 100;
		};
	}

}