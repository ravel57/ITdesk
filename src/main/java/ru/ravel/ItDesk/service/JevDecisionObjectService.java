package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.incident.SupportIncident;
import ru.ravel.ItDesk.model.jev.JevDecision;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.IncidentRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.ServiceRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Объекты из контекста решения Jev («client=1, message=124», «task=31»): подписи для журнала решений
 * и подробности для предпросмотра по нажатию.
 */
@Service
@RequiredArgsConstructor
public class JevDecisionObjectService {

	public static final Pattern REFERENCE = Pattern.compile("\\b(client|message|task|incident|service)=(\\d+)");

	private static final int LABEL_LENGTH = 48;
	private static final int TEXT_LENGTH = 2000;
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

	private final ClientRepository clientRepository;
	private final MessageRepository messageRepository;
	private final TaskRepository taskRepository;
	private final IncidentRepository incidentRepository;
	private final ServiceRepository serviceRepository;


	public record Reference(String type, Long id) {

		public String key() {
			return type + ":" + id;
		}
	}


	public static List<Reference> references(String context) {
		List<Reference> result = new ArrayList<>();
		if (context == null) {
			return result;
		}
		Matcher matcher = REFERENCE.matcher(context);
		while (matcher.find()) {
			result.add(new Reference(matcher.group(1), Long.valueOf(matcher.group(2))));
		}
		return result;
	}


	/**
	 * Подписи объектов всех решений страницы: «type:id» → «Иван Петров», «#31 Не работает 1С» и т. п.
	 * Удалённые объекты в карту не попадают.
	 */
	@Transactional(readOnly = true)
	public Map<String, String> labels(Collection<JevDecision> decisions) {
		Map<String, Set<Long>> ids = new HashMap<>();
		decisions.stream()
				.flatMap(decision -> references(decision.getContext()).stream())
				.forEach(reference -> ids.computeIfAbsent(reference.type(), type -> new HashSet<>()).add(reference.id()));
		Map<String, String> result = new LinkedHashMap<>();
		clientRepository.findAllById(ids.getOrDefault("client", Set.of()))
				.forEach(client -> result.put("client:" + client.getId(), clientName(client)));
		messageRepository.findAllById(ids.getOrDefault("message", Set.of()))
				.forEach(message -> result.put("message:" + message.getId(), messageLabel(message)));
		taskRepository.findAllById(ids.getOrDefault("task", Set.of()))
				.forEach(task -> result.put("task:" + task.getId(), shorten("#" + task.getId() + " " + Objects.toString(task.getName(), ""))));
		incidentRepository.findAllById(ids.getOrDefault("incident", Set.of()))
				.forEach(incident -> result.put("incident:" + incident.getId(), shorten(incidentTitle(incident))));
		serviceRepository.findAllById(ids.getOrDefault("service", Set.of()))
				.forEach(service -> result.put("service:" + service.getId(), shorten(service.getName())));
		return result;
	}


	/**
	 * Подробности объекта для предпросмотра. clientId — клиент из того же контекста (для ссылки на чат сообщения).
	 */
	@Transactional(readOnly = true)
	public Optional<Map<String, Object>> preview(String type, Long id, Long clientId) {
		return switch (type) {
			case "client" -> clientRepository.findById(id).map(this::clientPreview);
			case "message" -> messageRepository.findById(id).map(message -> messagePreview(message, clientId));
			case "task" -> taskRepository.findById(id).map(this::taskPreview);
			case "incident" -> incidentRepository.findById(id).map(this::incidentPreview);
			case "service" -> serviceRepository.findById(id).map(this::servicePreview);
			default -> Optional.empty();
		};
	}


	private Map<String, Object> clientPreview(Client client) {
		return preview("client", client.getId(), clientName(client), null, "/chats/" + client.getId(), fields(
				"Организация", client.getOrganization() == null ? null : client.getOrganization().getName(),
				"Email", client.getEmail()
		));
	}


	private Map<String, Object> messagePreview(Message message, Long clientId) {
		String author = message.getUser() == null ? "Клиент" : userName(message.getUser());
		String text = message.getText() == null || message.getText().isBlank()
				? (message.getFileName() == null ? null : "Файл: " + message.getFileName())
				: shorten(message.getText(), TEXT_LENGTH);
		return preview("message", message.getId(), "Сообщение #" + message.getId(), text,
				clientId == null ? null : "/chats/" + clientId, fields(
						"Автор", Boolean.TRUE.equals(message.getIsComment()) ? author + " (комментарий)" : author,
						"Время", format(message.getDate()),
						"Удалено", Boolean.TRUE.equals(message.getDeleted()) ? "да" : null
				));
	}


	private Map<String, Object> taskPreview(Task task) {
		return preview("task", task.getId(), "#" + task.getId() + " " + Objects.toString(task.getName(), ""), null,
				"/tasks?task=" + task.getId(), fields(
						"Статус", task.getStatus() == null ? null : task.getStatus().getName(),
						"Приоритет", task.getPriority() == null ? null : task.getPriority().getName(),
						"Тип", task.getType() == null ? null : task.getType().getType(),
						"Сервис", task.getService() == null ? null : task.getService().getName(),
						"Линия", task.getSupportLine() == null ? null : task.getSupportLine().getName(),
						"Исполнитель", task.getExecutor() == null ? null : userName(task.getExecutor()),
						"Создана", format(task.getCreatedAt()),
						"Закрыта", format(task.getClosedAt())
				));
	}


	private Map<String, Object> incidentPreview(SupportIncident incident) {
		return preview("incident", incident.getId(), incidentTitle(incident), null, "/incidents/" + incident.getId(), fields(
				"Статус", incident.getStatus() == null ? null : incident.getStatus().name(),
				"Важность", incident.getSeverity() == null ? null : incident.getSeverity().name(),
				"Сервисы", incident.getServices().stream().map(ru.ravel.ItDesk.model.Service::getName).collect(Collectors.joining(", "))
		));
	}


	private Map<String, Object> servicePreview(ru.ravel.ItDesk.model.Service service) {
		return preview("service", service.getId(), service.getName(), service.getDescription(),
				"/settings/services?serviceId=" + service.getId(), fields(
						"Код", service.getCode(),
						"Мониторинг", service.getMonitoringStatus() == null ? null : service.getMonitoringStatus().name()
				));
	}


	private Map<String, Object> preview(String type, Long id, String title, String text, String link, List<Map<String, String>> fields) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("type", type);
		result.put("id", id);
		result.put("title", title);
		result.put("text", text);
		result.put("link", link);
		result.put("fields", fields);
		return result;
	}


	/** Пары «подпись, значение»; пустые значения пропускаются. */
	private List<Map<String, String>> fields(String... pairs) {
		List<Map<String, String>> result = new ArrayList<>();
		for (int index = 0; index + 1 < pairs.length; index += 2) {
			if (pairs[index + 1] != null && !pairs[index + 1].isBlank()) {
				result.add(Map.of("label", pairs[index], "value", pairs[index + 1]));
			}
		}
		return result;
	}


	private String clientName(Client client) {
		String name = Stream.of(client.getFirstname(), client.getLastname())
				.filter(part -> part != null && !part.isBlank())
				.collect(Collectors.joining(" "));
		return shorten(name.isBlank() ? "Клиент #" + client.getId() : name);
	}


	private String messageLabel(Message message) {
		if (message.getText() != null && !message.getText().isBlank()) {
			return "«" + shorten(message.getText().replaceAll("\\s+", " ").trim(), LABEL_LENGTH - 2) + "»";
		}
		return message.getFileName() == null ? "Сообщение #" + message.getId() : shorten("Файл " + message.getFileName());
	}


	private String userName(User user) {
		String name = Stream.of(user.getLastname(), user.getFirstname())
				.filter(part -> part != null && !part.isBlank())
				.collect(Collectors.joining(" "));
		return name.isBlank() ? user.getUsername() : name;
	}


	private String incidentTitle(SupportIncident incident) {
		String number = incident.getIncidentNumber() == null ? "#" + incident.getId() : incident.getIncidentNumber();
		return number + " " + Objects.toString(incident.getTitle(), "");
	}


	private String format(ZonedDateTime value) {
		return value == null ? null : value.withZoneSameInstant(ZoneId.systemDefault()).format(DATE_TIME);
	}


	private String shorten(String value) {
		return shorten(value, LABEL_LENGTH);
	}


	private String shorten(String value, int length) {
		if (value == null) {
			return "";
		}
		String trimmed = value.trim();
		return trimmed.length() <= length ? trimmed : trimmed.substring(0, length - 1).trim() + "…";
	}

}
