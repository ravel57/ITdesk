package ru.ravel.ItDesk.service;

import com.pengrad.telegrambot.TelegramException;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.component.PendingFileUploadRegistry;
import ru.ravel.ItDesk.dto.*;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
@RequiredArgsConstructor
public class ClientService {
	private static final Integer PAGE_LIMIT = 100;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private final ClientRepository clientsRepository;
	private final TaskRepository taskRepository;
	private final MessageRepository messageRepository;
	private final TelegramService telegramService;
	private final UserService userService;
	private final OrganizationService organizationService;
	private final SlaRepository slaRepository;
	private final WebSocketService webSocketService;
	private final EventPublisher eventPublisher;
	private final UserRepository userRepository;
	private final GlobalSearchService globalSearchService;
	private final SlaPauseRepository slaPauseRepository;
	private final UserNotificationService userNotificationService;
	private final PendingFileUploadRegistry pendingFileUploadRegistry;

	private final Map<String, ExecuteFuture> clientUserMapTypingExecutorServices = new ConcurrentHashMap<>();
	private final Map<String, ExecuteFuture> clientUserMapWatchingExecutorServices = new ConcurrentHashMap<>();
	private final Map<Long, Set<User>> typingUsers = new ConcurrentHashMap<>();
	private final Map<Long, Set<User>> watchingUsers = new ConcurrentHashMap<>();

	@Value("${app.is-demo:false}")
	private boolean isDemo;


	@Transactional(readOnly = true)
	public List<Client> getClients() {
		boolean hideDeletedLastMessage = userService.isObserver(userService.getCurrentUser());
		return prepareClientsForList(
				userService.filterClientsByCurrentUser(clientsRepository.findAll()),
				hideDeletedLastMessage
		);
	}


	@Transactional(readOnly = true)
	public List<Client> getClientsForSystem() {
		return prepareClientsForList(clientsRepository.findAll(), false);
	}


	@Transactional(readOnly = true)
	public List<Client> getClientsForUser(User user) {
		boolean hideDeletedLastMessage = userService.isObserver(user);
		return prepareClientsForList(
				userService.filterClientsByUser(clientsRepository.findAll(), user),
				hideDeletedLastMessage
		);
	}


	private List<Client> prepareClientsForList(List<Client> clients, boolean hideDeletedLastMessage) {
		Map<Long, Message> latestVisibleMessages = hideDeletedLastMessage
				? loadLatestVisibleMessages(clients)
				: Map.of();

		clients.forEach(client -> {
			Collection<Message> messages = safeCollection(client.getMessages());
			client.setMessagesRevision(buildMessagesRevision(messages));

			// Для менеджера организации время активности тоже без комментариев и удалённых, иначе оно их выдаёт
			Message latestActivityMessage = findLatestMessage(messages, hideDeletedLastMessage);
			client.setLastActivityDate(latestActivityMessage == null ? null : latestActivityMessage.getDate());

			if (hideDeletedLastMessage) {
				Message latestVisibleMessage = latestVisibleMessages.get(client.getId());
				// Основной источник — пакетный SQL. Fallback по managed-коллекции нужен для
				// старых/нестандартных данных: карточка не должна терять предыдущее
				// неудалённое сообщение только потому, что projection не вернул строку.
				if (latestVisibleMessage == null) {
					latestVisibleMessage = findLatestMessage(messages, true);
				}
				client.setLastMessage(latestVisibleMessage);
			} else {
				client.setLastMessage(latestActivityMessage);
			}
			client.setUnreadMessagesCount(messages.stream()
					.filter(message -> Boolean.FALSE.equals(message.getIsRead()))
					.filter(message -> Boolean.FALSE.equals(message.getIsSent()))
					.filter(message -> !Boolean.TRUE.equals(message.getIsComment()))
					.filter(message -> !Boolean.TRUE.equals(message.getDeleted()))
					.count());
			client.setFirstUnansweredMessageDate(getFirstUnansweredMessageDate(messages));
			client.setTypingUsers(Objects.requireNonNullElse(typingUsers.get(client.getId()), Collections.emptySet()));
			client.setWatchingUsers(Objects.requireNonNullElse(watchingUsers.get(client.getId()), Collections.emptySet()));
			fillClientTaskSummary(client);
			if (client.getMessageFrom() == null) {
				client.setSourceChannel(null);
				return;
			}
			switch (client.getMessageFrom()) {
				case TELEGRAM -> {
					TgBot tgBot = Objects.requireNonNullElse(client.getTgBot(), new TgBot());
					client.setSourceChannel(tgBot.getName());
				}
				case WHATSAPP -> {
					WhatsappAccount whatsappAccount = Objects.requireNonNullElse(client.getWhatsappAccount(), new WhatsappAccount());
					client.setSourceChannel(whatsappAccount.getName());
				}
				case EMAIL -> {
					EmailAccount emailAccount = Objects.requireNonNullElse(client.getEmailAccountSender(), new EmailAccount());
					client.setSourceChannel(emailAccount.getName());
				}
				case PORTAL -> client.setSourceChannel("Личный кабинет");
			}
		});
		return clients;
	}


	private Map<Long, Message> loadLatestVisibleMessages(List<Client> clients) {
		List<Long> clientIds = clients.stream()
				.filter(Objects::nonNull)
				.map(Client::getId)
				.filter(Objects::nonNull)
				.toList();
		if (clientIds.isEmpty()) {
			return Map.of();
		}

		List<MessageRepository.LatestVisibleMessageRow> rows =
				messageRepository.findLatestVisibleMessageIdsByClientIds(clientIds);
		if (rows.isEmpty()) {
			return Map.of();
		}

		Map<Long, Message> messagesById = messageRepository.findAllById(
				rows.stream()
						.map(MessageRepository.LatestVisibleMessageRow::getMessageId)
						.filter(Objects::nonNull)
						.distinct()
						.toList()
		).stream().collect(java.util.stream.Collectors.toMap(Message::getId, message -> message));

		Map<Long, Message> result = new HashMap<>();
		for (MessageRepository.LatestVisibleMessageRow row : rows) {
			Message message = messagesById.get(row.getMessageId());
			if (message != null) {
				result.put(row.getClientId(), message);
			}
		}
		return result;
	}


	private Message findLatestMessage(Collection<Message> messages, boolean visibleOnly) {
		return safeCollection(messages).stream()
				.filter(Objects::nonNull)
				.filter(message -> !visibleOnly || !isHiddenFromObserver(message))
				.max(Comparator
						.comparing(Message::getDate, Comparator.nullsFirst(Comparator.naturalOrder()))
						.thenComparing(Message::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
				.orElse(null);
	}


	/** Менеджер организации (OBSERVER) не видит удалённые сообщения и внутренние комментарии поддержки. */
	private static boolean isHiddenFromObserver(Message message) {
		return Boolean.TRUE.equals(message.getDeleted()) || Boolean.TRUE.equals(message.getIsComment());
	}


	private String buildMessagesRevision(Collection<Message> messages) {
		List<Message> orderedMessages = safeCollection(messages).stream()
				.filter(Objects::nonNull)
				.sorted(Comparator.comparing(
						Message::getId,
						Comparator.nullsFirst(Comparator.naturalOrder())
				))
				.toList();

		// FNV-1a 64 bit. Нужна компактная стабильная сигнатура содержимого
		// истории между последовательными /user/topic/clients/ refresh.
		long hash = 0xcbf29ce484222325L;
		for (Message message : orderedMessages) {
			hash = mixMessagesRevision(hash, message.getId());
			hash = mixMessagesRevision(hash, message.getDate());
			hash = mixMessagesRevision(hash, message.getEditedAt());
			hash = mixMessagesRevision(hash, message.getDeleted());
			hash = mixMessagesRevision(hash, message.getText());
			hash = mixMessagesRevision(hash, message.getFileUuid());
		}
		return orderedMessages.size() + ":" + Long.toUnsignedString(hash, 16);
	}


	private long mixMessagesRevision(long hash, Object value) {
		String text = Objects.toString(value, "<null>");
		for (int index = 0; index < text.length(); index++) {
			hash ^= text.charAt(index);
			hash *= 0x100000001b3L;
		}
		hash ^= 0xff;
		hash *= 0x100000001b3L;
		return hash;
	}


	private void fillClientTaskSummary(Client client) {
		if (client == null || client.getId() == null) {
			return;
		}
		User currentUser;
		try {
			currentUser = userService.getCurrentUser();
		} catch (Exception ignored) {
			currentUser = null;
		}
		User accessUser = currentUser;
		List<Task> accessibleOpenTasks = safeCollection(client.getTasks()).stream()
				.filter(Objects::nonNull)
				.filter(task -> accessUser == null || userService.canUserAccessTask(accessUser, client, task))
				.filter(task -> !Boolean.TRUE.equals(task.getCompleted()))
				.filter(task -> !Boolean.TRUE.equals(task.getFrozen()))
				.toList();

		client.setOpenTasksCount((long) accessibleOpenTasks.size());
		client.setTasksWithoutAssigneeCount(accessibleOpenTasks.stream()
				.filter(task -> task.getExecutor() == null)
				.count());
		client.setHasCriticalTasks(accessibleOpenTasks.stream()
				.map(Task::getPriority)
				.filter(Objects::nonNull)
				.anyMatch(priority -> Boolean.TRUE.equals(priority.getCritical())));

		Long currentUserId = currentUser == null ? null : currentUser.getId();
		if (currentUserId == null) {
			client.setTaskPingCount(0L);
			client.setHasTaskPing(false);
		} else {
			long taskPingCount = accessibleOpenTasks.stream()
					.filter(task -> task.getUnreadPingTasksMessages() != null)
					.filter(task -> Boolean.TRUE.equals(task.getUnreadPingTasksMessages().get(currentUserId)))
					.count();
			client.setTaskPingCount(taskPingCount);
			client.setHasTaskPing(taskPingCount > 0);
		}

		accessibleOpenTasks.stream()
				.filter(task -> task.getSla() != null)
				.filter(task -> getDeadline(task.getSla()) != null)
				.min(Comparator.comparing(task -> getDeadline(task.getSla())))
				.ifPresent(task -> {
					Sla sla = task.getSla();
					client.setMinimalSlaTaskId(task.getId());
					client.setMinimalSlaStartDate(sla.getStartDate());
					client.setMinimalSlaDurationSeconds(sla.getDuration() == null ? null : sla.getDuration().getSeconds());
					client.setMinimalSlaDeadline(getDeadline(sla));
					client.setMinimalSlaPaused(isPaused(sla));
				});
	}


	@Transactional
	public void pauseSla(Sla sla, String reason) {
		if (sla == null) {
			return;
		}
		boolean alreadyPaused = safeCollection(sla.getPauses()).stream().anyMatch(p -> p.getEndedAt() == null);
		if (alreadyPaused) {
			return;
		}
		SlaPause pause = new SlaPause();
		pause.setSla(sla);
		pause.setStartedAt(ZonedDateTime.now());
		pause.setEndedAt(null);
		pause.setReason(reason);
		if (sla.getPauses() == null) {
			sla.setPauses(new ArrayList<>());
		}
		sla.getPauses().add(pause);
		slaRepository.save(sla);
		publishSlaPauseHistory(sla, true, reason);
	}


	@Transactional
	public void resumeSla(Sla sla) {
		if (sla == null) {
			return;
		}
		SlaPause active = safeCollection(sla.getPauses()).stream()
				.filter(p -> p.getEndedAt() == null)
				.findFirst()
				.orElse(null);
		if (active == null) {
			return;
		}
		String reason = active.getReason();
		active.setEndedAt(ZonedDateTime.now());
		slaRepository.save(sla);
		publishSlaPauseHistory(sla, false, reason);
	}


	public Duration getPausedDuration(Sla sla) {
		if (sla == null || sla.getId() == null) {
			return Duration.ZERO;
		}
		return getPausedDurationBySlaId(sla.getId());
	}


	private Duration getPausedDurationBySlaId(Long slaId) {
		if (slaId == null) {
			return Duration.ZERO;
		}

		ZonedDateTime now = ZonedDateTime.now();

		long seconds = slaPauseRepository.findAllBySlaId(slaId).stream()
				.filter(pause -> pause.getStartedAt() != null)
				.mapToLong(pause -> {
					ZonedDateTime end = pause.getEndedAt() == null ? now : pause.getEndedAt();
					return Math.max(0, Duration.between(pause.getStartedAt(), end).getSeconds());
				})
				.sum();

		return Duration.ofSeconds(seconds);
	}


	public ZonedDateTime getDeadline(Sla sla) {
		if (sla == null) {
			return null;
		}
		if (sla.getStartDate() == null || sla.getDuration() == null) {
			return null;
		}
		return sla.getStartDate()
				.plus(sla.getDuration())
				.plus(getPausedDuration(sla));
	}


	public Duration getRemaining(Sla sla) {
		ZonedDateTime deadline = getDeadline(sla);
		if (deadline == null) {
			return Duration.ZERO;
		}
		return Duration.between(ZonedDateTime.now(), deadline);
	}


	public boolean isPaused(Sla sla) {
		return sla != null
				&& sla.getId() != null
				&& slaPauseRepository.findFirstBySlaIdAndEndedAtIsNullOrderByStartedAtDesc(sla.getId()).isPresent();
	}


	@Transactional
	public Message sendMessage(Long clientId, Message message) {
		if (message == null) {
			throw new IllegalArgumentException("message must not be null");
		}
		return sendMessageWithUser(clientId, message, userService.getCurrentUser());
	}


	@Transactional
	public Message sendMessageWithUser(Long clientId, @NotNull Message message, User user) {
		return sendMessageInternal(clientId, message, user, true);
	}


	@Transactional
	public Message sendMessageForSystem(Long clientId, @NotNull Message message) {
		return sendMessageInternal(clientId, message, SystemUser.getInstance(), false);
	}


	private Message sendMessageInternal(Long clientId, @NotNull Message message, User user, boolean checkCurrentUserAccess) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		message.setDate(ZonedDateTime.now());
		message.setUser(user);
		message.setIsSent(true);
		message.setIsRead(true);
		message.setIsComment(Boolean.TRUE.equals(message.getIsComment()));
		Client client = checkCurrentUserAccess
				? getClientForCurrentUser(clientId)
				: clientsRepository.findById(clientId).orElseThrow();
		message.setFromSource(Boolean.TRUE.equals(message.getIsComment()) ? "INTERNAL"
				: client.getMessageFrom() == null ? null : client.getMessageFrom().name());
		if (checkCurrentUserAccess && hasFileUuid(message)) {
			pendingFileUploadRegistry.assertOwned(message.getFileUuid(), user.getUsername());
		}
		hydrateReplyData(message, client.getMessages());
		Message savedMessage = messageRepository.saveAndFlush(message);
		hydrateReplyData(savedMessage, client.getMessages());
		if (!Boolean.TRUE.equals(message.getIsComment()) && !isDemo) {
			if (client.getMessageFrom() == null) {
				logger.warn("message external delivery skipped: client messageFrom is null, clientId={}", client.getId());
			} else if (client.getMessageFrom() != MessageFrom.PORTAL) {
				// The portal reads the shared history and receives the socket update below.
				eventPublisher.publish(TriggerType.COMMUNICATION_SEND_REQUESTED, Map.of(
						"clientId", client.getId(),
						"messageId", savedMessage.getId(),
						"channel", client.getMessageFrom().name()
				));
			}
		} else {
			// pings
			Pattern pattern = Pattern.compile("@\\[(.+?)]");
			Matcher matcher = pattern.matcher(Objects.requireNonNullElse(savedMessage.getText(), ""));
			if (client.getUnreadPingMessages() == null) {
				client.setUnreadPingMessages(new HashMap<>());
			}
			Collection<User> users = safeCollection(userService.getUsers());
			while (matcher.find()) {
				String fullName = matcher.group(1);
				users.stream()
						.filter(u -> {
						String firstLast = (Objects.toString(u.getFirstname(), "") + " " + Objects.toString(u.getLastname(), "")).trim();
						String lastFirst = (Objects.toString(u.getLastname(), "") + " " + Objects.toString(u.getFirstname(), "")).trim();
						return fullName.equals(firstLast) || fullName.equals(lastFirst) || fullName.equals(u.getUsername());
					})
						.findFirst()
						.ifPresent(u -> {
							client.getUnreadPingMessages().put(u.getId(), true);
							eventPublisher.publish(TriggerType.MESSAGE_MENTIONED_USER, eventPayload("client", client, "message", savedMessage, "mentionedUser", u));
							userNotificationService.send(new UserNotification(
									UserNotificationEvent.MENTIONED_USER,
									Objects.toString(savedMessage.getText(), ""),
									u.getId()
							));
						});
			}
		}
		if (client.getMessages() == null) {
			client.setMessages(new ArrayList<>());
		}
		boolean alreadyExists = client.getMessages().stream()
				.anyMatch(existingMessage -> Objects.equals(existingMessage.getId(), savedMessage.getId()));
		if (!alreadyExists) {
			client.getMessages().add(savedMessage);
		}
		Client savedClient = clientsRepository.saveAndFlush(client);
		if (checkCurrentUserAccess && hasFileUuid(savedMessage)) {
			pendingFileUploadRegistry.consumeAfterCommit(savedMessage.getFileUuid());
		}
		globalSearchService.indexClient(savedClient);
		globalSearchService.indexClientMessage(savedClient, savedMessage);
		eventPublisher.publish(TriggerType.MESSAGE_OUTGOING, eventPayload(
				"message", savedMessage,
				"client", savedClient
		));
		webSocketService.sendNewMessages(new ClientMessage(savedClient, savedMessage));
		return savedMessage;
	}


	@Transactional
	public Client markReadAndReturnClient(ClientUser clientUser, User authenticatedUser) {
		if (clientUser == null || clientUser.getClientId() == null || authenticatedUser == null || authenticatedUser.getId() == null) {
			logger.warn("mark-read skipped: clientId or authenticated user is null");
			return null;
		}
		Optional<Client> clientOpt = clientsRepository.findById(clientUser.getClientId());
		if (clientOpt.isEmpty()) {
			logger.warn("mark-read skipped: client not found, clientId={}", clientUser.getClientId());
			return null;
		}
		Client client = clientOpt.get();
		User user = authenticatedUser;
		userService.assertUserCanAccessClient(user, client);
		String watchKey = "%d:%d".formatted(client.getId(), user.getId());
		Set<User> currentWatchers = watchingUsers.computeIfAbsent(client.getId(), ignored -> new ConcurrentSkipListSet<>());
		boolean alreadyWatching = currentWatchers.contains(user);
		if (!alreadyWatching) {
			currentWatchers.add(user);
			eventPublisher.publish(TriggerType.USER_OPEN_DIALOG, eventPayload(
					"client", client,
					"user", user
			));
		}
		ExecuteFuture executeFuture = clientUserMapWatchingExecutorServices.get(watchKey);
		if (executeFuture != null) {
			executeFuture.getFuture().cancel(true);
		} else {
			executeFuture = new ExecuteFuture();
			clientUserMapWatchingExecutorServices.put(watchKey, executeFuture);
		}
		UserActionWaiter task = new UserActionWaiter(client, user, watchingUsers, logger, 30_000, eventPublisher, watchKey);
		executeFuture.setFuture(executeFuture.getExecutor().submit(task));

		if (client.getUnreadPingMessages() != null) {
			client.getUnreadPingMessages().put(user.getId(), false);
		}

		List<Message> messages = safeCollection(client.getMessages()).stream()
				.filter(message -> Boolean.FALSE.equals(message.getIsRead()))
				.filter(message -> Boolean.FALSE.equals(message.getIsSent()))
				.peek(message -> message.setIsRead(true))
				.toList();
		messageRepository.saveAll(messages);
		clientsRepository.save(client);
		return client;
	}


	public Client updateClient(Long clientId, @NotNull Map<String, Object> c) {
		if (c == null) {
			throw new IllegalArgumentException("client payload must not be null");
		}
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		Client client = getClientForCurrentUser(clientId);
		Object organizationName = c.get("organization");
		Organization organization = safeCollection(organizationService.getOrganizations()).stream()
				.filter(it -> Objects.equals(it.getName(), organizationName))
				.findFirst().orElse(null);
		if (organization != null) {
			// Оператор с ограниченным списком организаций не может перенести клиента в чужую организацию.
			userService.assertCurrentUserCanAccessOrganizationId(organization.getId());
		} else {
			// Клиент без организации виден только пользователям с доступом ко всем организациям.
			userService.assertCurrentUserCanAccessClient(Client.builder().id(client.getId()).build());
		}
		client.setFirstname(Objects.toString(c.get("firstname"), null));
		client.setLastname(Objects.toString(c.get("lastname"), null));
		client.setOrganization(organization);
		client.setMoreInfo(Objects.toString(c.get("moreInfo"), null));
		clientsRepository.save(client);
		globalSearchService.indexClient(client);
		eventPublisher.publish(TriggerType.CLIENT_UPDATED, eventPayload("client", client));
		return client;
	}


	public void typing(@NotNull ClientUserText clientUserText, User authenticatedUser) {
		if (clientUserText == null || clientUserText.getClientId() == null || authenticatedUser == null || authenticatedUser.getId() == null) {
			logger.warn("typing skipped: clientId or authenticated user is null");
			return;
		}
		Client client = clientsRepository.findById(clientUserText.getClientId()).orElseThrow();
		User user = authenticatedUser;
		userService.assertUserCanAccessClient(user, client);
		if (client.getTypingMessageText() == null) {
			client.setTypingMessageText(new HashMap<>());
		}
		client.getTypingMessageText().put(user.getId(), Objects.toString(clientUserText.getText(), ""));
		clientsRepository.save(client);
		String typingKey = "%d:%d".formatted(client.getId(), user.getId());
		Set<User> currentTypingUsers = typingUsers.computeIfAbsent(client.getId(), ignored -> new ConcurrentSkipListSet<>());
		currentTypingUsers.add(user);
		ExecuteFuture executeFuture = clientUserMapTypingExecutorServices.get(typingKey);
		if (executeFuture != null) {
			executeFuture.getFuture().cancel(true);
		} else {
			executeFuture = new ExecuteFuture();
			clientUserMapTypingExecutorServices.put(typingKey, executeFuture);
		}
		UserActionWaiter task = new UserActionWaiter(client, user, typingUsers, logger, 30_000, eventPublisher, typingKey);
		executeFuture.setFuture(executeFuture.getExecutor().submit(task));
	}


	@Transactional
	public MessageTask linkToTask(@NotNull MessageTask messageTask) {
		if (messageTask == null) {
			throw new IllegalArgumentException("messageTask must not be null");
		}
		if (messageTask.getTask() == null || messageTask.getTask().getId() == null) {
			throw new IllegalArgumentException("task.id must not be null");
		}
		if (messageTask.getMessage() == null || messageTask.getMessage().getId() == null) {
			throw new IllegalArgumentException("message.id must not be null");
		}

		Long taskId = messageTask.getTask().getId();
		Long newLinkedMessageId = messageTask.getMessage().getId();
		Client client = getClientByTaskForCurrentUser(taskId);
		Task task = taskRepository.findById(taskId).orElseThrow();
		Message newLinkedMessage = messageRepository.findById(newLinkedMessageId).orElseThrow();

		boolean belongsToClient = safeCollection(client.getMessages()).stream()
				.anyMatch(message -> Objects.equals(message.getId(), newLinkedMessageId));
		if (!belongsToClient) {
			throw new IllegalArgumentException("message does not belong to task client");
		}

		Long oldLinkedMessageId = task.getLinkedMessageId();
		unlinkMessageFromOtherTasks(client, taskId, newLinkedMessageId, newLinkedMessage);
		if (Objects.equals(oldLinkedMessageId, newLinkedMessageId)) {
			return messageTask;
		}

		Message oldLinkedMessage = oldLinkedMessageId == null
				? null
				: messageRepository.findById(oldLinkedMessageId).orElse(null);
		task.setLinkedMessageId(newLinkedMessageId);
		Task savedTask = taskRepository.saveAndFlush(task);

		String oldValue = describeLinkedMessage(oldLinkedMessageId, oldLinkedMessage);
		String newValue = describeLinkedMessage(newLinkedMessageId, newLinkedMessage);
		eventPublisher.publish(TriggerType.TASK_UPDATED, eventPayload(
				"task", savedTask,
				"client", client,
				"message", newLinkedMessage,
				"oldLinkedMessageId", oldLinkedMessageId,
				"newLinkedMessageId", newLinkedMessageId,
				"changes", List.of(Map.of(
						"field", "linkedMessageId",
						"label", "Связанное сообщение",
						"oldValue", oldValue,
						"newValue", newValue
				))
		));
		globalSearchService.indexTask(client, savedTask);
		return messageTask;
	}


	public boolean deleteMessage(Long clientId, Long messageId) throws IllegalArgumentException {
		if (clientId == null || messageId == null) {
			throw new IllegalArgumentException("clientId and messageId must not be null");
		}
		Client client = getClientForCurrentUser(clientId);
		Message message = messageRepository.findById(messageId).orElseThrow();
		boolean belongsToClient = safeCollection(client.getMessages()).stream()
				.anyMatch(m -> Objects.equals(m.getId(), messageId));
		if (!belongsToClient) {
			throw new IllegalArgumentException("message does not belong to client");
		}
		if (client.getMessageFrom() != null) {
			switch (client.getMessageFrom()) {
				case TELEGRAM -> {
					try {
						telegramService.deleteMessage(client, message);
					} catch (TelegramException e) {
						return false;
					}
				}
				case WHATSAPP, EMAIL, PORTAL -> {
				}
			}
		}
		message.setDeleted(true);
		messageRepository.save(message);
		globalSearchService.deleteDocument("CLIENT_MESSAGE:" + message.getId());
		globalSearchService.deleteDocument("TASK_MESSAGE:" + message.getId());
		eventPublisher.publish(TriggerType.MESSAGE_DELETED, eventPayload("client", client, "message", message));
		webSocketService.deletedMessage(new ClientMessage(client, message));
		return true;
	}


	@Transactional
	public Message editMessage(Long clientId, Long messageId, @NotNull Message payload) throws IllegalArgumentException {
		if (clientId == null || messageId == null) {
			throw new IllegalArgumentException("clientId and messageId must not be null");
		}
		Client client = getClientForCurrentUser(clientId);
		Message message = messageRepository.findById(messageId).orElseThrow();
		boolean belongsToClient = safeCollection(client.getMessages()).stream()
				.anyMatch(m -> Objects.equals(m.getId(), messageId));
		if (!belongsToClient) {
			throw new IllegalArgumentException("message does not belong to client");
		}
		if (Boolean.TRUE.equals(message.getDeleted())) {
			throw new IllegalStateException("deleted message cannot be edited");
		}
		assertCurrentUserCanEditMessage(message);
		String newText = Objects.toString(payload.getText(), "").trim();
		if (newText.isBlank()) {
			throw new IllegalArgumentException("message text must not be blank");
		}
		String oldText = message.getText();
		message.setText(newText);
		boolean shouldEditExternalMessage =
				Boolean.TRUE.equals(message.getIsSent()) &&
						!Boolean.TRUE.equals(message.getIsComment()) &&
						message.getMessengerMessageId() != null &&
						message.getFileUuid() == null &&
						!isDemo;

		if (shouldEditExternalMessage && client.getMessageFrom() != null) {
			try {
				switch (client.getMessageFrom()) {
					case TELEGRAM -> telegramService.editMessage(client, message);
					case EMAIL, WHATSAPP, PORTAL -> {
					}
				}
			} catch (TelegramException e) {
				message.setText(oldText);
				throw new IllegalStateException("external message edit failed", e);
			}
		}
		message.setEditedAt(ZonedDateTime.now());
		Message savedMessage = messageRepository.saveAndFlush(message);
		safeCollection(client.getMessages()).stream()
				.filter(m -> Objects.equals(m.getId(), savedMessage.getId()))
				.findFirst()
				.ifPresent(m -> {
					m.setText(savedMessage.getText());
					m.setEditedAt(savedMessage.getEditedAt());
				});
		clientsRepository.save(client);
		globalSearchService.indexClient(client);
		globalSearchService.indexClientMessage(client, savedMessage);
		eventPublisher.publish(
				TriggerType.MESSAGE_EDITED,
				eventPayload("client", client, "message", savedMessage)
		);
		webSocketService.editedMessage(new ClientMessage(client, savedMessage));
		return savedMessage;
	}


	/**
	 * Редактировать можно только исходящие сообщения поддержки и комментарии: автору,
	 * либо ADMIN/MANAGER. Входящие сообщения клиента не редактируются никогда.
	 */
	private void assertCurrentUserCanEditMessage(@NotNull Message message) {
		boolean staffMessage = Boolean.TRUE.equals(message.getIsSent()) || Boolean.TRUE.equals(message.getIsComment());
		if (!staffMessage) {
			throw new org.springframework.security.access.AccessDeniedException("Сообщение клиента нельзя редактировать");
		}
		User currentUser = userService.getCurrentUser();
		if (userService.isAdmin(currentUser) || userService.isManager(currentUser)) {
			return;
		}
		boolean author = currentUser != null && currentUser.getId() != null
				&& message.getUser() != null
				&& Objects.equals(message.getUser().getId(), currentUser.getId());
		if (!author) {
			throw new org.springframework.security.access.AccessDeniedException("Редактировать можно только свои сообщения");
		}
	}


	public Boolean deleteClient(Long clientId) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		Client client = getClientForCurrentUser(clientId);
		safeCollection(client.getMessages()).forEach(message ->
				globalSearchService.deleteDocument("CLIENT_MESSAGE:" + message.getId())
		);
		safeCollection(client.getTasks()).forEach(task -> {
			globalSearchService.deleteDocument("TASK:" + task.getId());

			safeCollection(task.getMessages()).forEach(message ->
					globalSearchService.deleteDocument("TASK_MESSAGE:" + message.getId())
			);
		});
		globalSearchService.deleteDocument("CLIENT:" + client.getId());
		clientsRepository.delete(client);
		eventPublisher.publish(TriggerType.CLIENT_DELETED, eventPayload("client", client));
		return true;
	}


	public List<Client> getClientsForObserver(User observer) {
		if (observer == null || observer.getAvailableOrganizations() == null) {
			return Collections.emptyList();
		}
		return getClients().stream()
				.filter(client -> observer.getAvailableOrganizations().contains(client.getOrganization()))
				.toList();
	}


	@Transactional
	public Message addTaskMessage(Long taskId, @NotNull Message message) {
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		Client client = getClientByTaskForCurrentUser(taskId);
		User currentUser = userService.getCurrentUser();
		message.setDate(ZonedDateTime.now());
		message.setUser(currentUser);
		message.setIsSent(Boolean.TRUE.equals(message.getIsSent()));
		message.setIsRead(Boolean.TRUE.equals(message.getIsRead()));
		message.setIsComment(Boolean.TRUE.equals(message.getIsComment()));
		if (hasFileUuid(message)) {
			pendingFileUploadRegistry.assertOwned(message.getFileUuid(), currentUser.getUsername());
		}
		Message savedMessage = messageRepository.saveAndFlush(message);
		Task task = taskRepository.findById(taskId).orElseThrow();
		if (task.getMessages() == null) {
			task.setMessages(new ArrayList<>());
		}
		task.getMessages().add(savedMessage);
		task.setLastActivity(savedMessage.getDate());
		if (task.getFirstResponseAt() == null && !Boolean.TRUE.equals(savedMessage.getIsComment())) {
			task.setFirstResponseAt(savedMessage.getDate());
		}

		// pings
		Pattern pattern = Pattern.compile("@\\[(.+?)]");
		Matcher matcher = pattern.matcher(Objects.requireNonNullElse(savedMessage.getText(), ""));
		if (task.getUnreadPingTasksMessages() == null) {
			task.setUnreadPingTasksMessages(new HashMap<>());
		}
		Collection<User> users = safeCollection(userService.getUsers());
		while (matcher.find()) {
			String fullName = matcher.group(1);
			users.stream()
					.filter(u -> {
						String firstLast = (Objects.toString(u.getFirstname(), "") + " " + Objects.toString(u.getLastname(), "")).trim();
						String lastFirst = (Objects.toString(u.getLastname(), "") + " " + Objects.toString(u.getFirstname(), "")).trim();
						return fullName.equals(firstLast) || fullName.equals(lastFirst) || fullName.equals(u.getUsername());
					})
					.findFirst()
					.ifPresent(u -> {
						task.getUnreadPingTasksMessages().put(u.getId(), true);
						if (task.getAccessUsers() == null) {
							task.setAccessUsers(new LinkedHashSet<>());
						}
						task.getAccessUsers().add(u);
						eventPublisher.publish(
								TriggerType.TASK_MESSAGE_MENTIONED_USER,
								eventPayload(
										"task", task,
										"message", savedMessage,
										"mentionedUser", u
								)
						);
						userNotificationService.send(new UserNotification(
								UserNotificationEvent.MENTIONED_USER_IN_TASK_CHAT,
								Objects.toString(task.getName(), ""),
								u.getId()
						));
					});
		}
		if (task.getExecutor() != null) {
			userNotificationService.send(new UserNotification(
					UserNotificationEvent.NEW_CHAT_MESSAGE,
					task.getName(),
					task.getExecutor().getId()
			));
		}
		Task savedTask = taskRepository.saveAndFlush(task);
		if (hasFileUuid(savedMessage)) {
			pendingFileUploadRegistry.consumeAfterCommit(savedMessage.getFileUuid());
		}
		client = clientsRepository.findByTaskId(taskId).orElse(client);
		globalSearchService.indexTask(client, savedTask);
		globalSearchService.indexTaskMessage(client, savedTask, savedMessage);
		return savedMessage;
	}


	private boolean hasFileUuid(Message message) {
		return message != null && message.getFileUuid() != null && !message.getFileUuid().isBlank();
	}


	private void hydrateReplyData(Message message, Collection<Message> messages) {
		if (message == null || message.getReplyMessageId() == null) {
			return;
		}
		Message replyMessage = safeCollection(messages).stream()
				.filter(item -> Objects.equals(item.getId(), message.getReplyMessageId()))
				.findFirst()
				.orElse(null);
		if (replyMessage == null) {
			return;
		}
		message.setReplyMessageText(Objects.toString(replyMessage.getText(), ""));
		if (message.getReplyFileType() == null) {
			message.setReplyFileType(replyMessage.getFileType());
		}
		if (message.getReplyUuid() == null && replyMessage.getFileUuid() != null && !replyMessage.getFileUuid().isBlank()) {
			try {
				message.setReplyUuid(UUID.fromString(replyMessage.getFileUuid()));
			} catch (Exception ignored) {
			}
		}
	}


	@Transactional(readOnly = true)
	public PageMessages getPageOfMessages(Long clientId, Integer page) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		int safePage = Math.max(1, Objects.requireNonNullElse(page, 1));
		int offset = Math.max(0, PAGE_LIMIT * (safePage - 1));
		Client client = getClientForCurrentUser(clientId);
		User currentUser = userService.getCurrentUser();
		boolean hideDeleted = userService.isObserver(currentUser);
		List<Message> loadedDesc = hideDeleted
				? messageRepository.findVisibleClientMessagesPageFromEnd(
						client.getId(),
						PAGE_LIMIT + 1,
						offset
				)
				: messageRepository.findClientMessagesPageFromEnd(
						client.getId(),
						PAGE_LIMIT + 1,
						offset
				);
		boolean hasOlderMessages = loadedDesc.size() > PAGE_LIMIT;
		List<Message> messages = loadedDesc.stream()
				.limit(PAGE_LIMIT)
				.sorted(Comparator
						.comparing(Message::getDate, Comparator.nullsFirst(Comparator.naturalOrder()))
						.thenComparing(Message::getId, Comparator.nullsFirst(Comparator.naturalOrder()))
				)
				.toList();
		fillReplyDataForPage(messages, hideDeleted);
		applyLinkedTaskIds(clientId, messages);
		return new PageMessages(messages, !hasOlderMessages);
	}


	@Transactional(readOnly = true)
	public LinkedMessagePage getMessagesUntilLinkedMessage(Long clientId, Long linkedMessageId) {
		if (clientId == null || linkedMessageId == null) {
			throw new IllegalArgumentException("clientId and linkedMessageId must not be null");
		}
		Message message = messageRepository.findById(linkedMessageId).orElseThrow();
		Client client = getClientForCurrentUser(clientId);
		User currentUser = userService.getCurrentUser();
		long messagesAfter = userService.isObserver(currentUser)
				? messageRepository.countVisibleClientMessagesAfterMessage(client.getId(), message.getId())
				: messageRepository.countClientMessagesAfterMessage(client.getId(), message.getId());
		int page = Math.max(1, (int) Math.ceil((messagesAfter + 1.0) / PAGE_LIMIT));
		PageMessages pageMessages = getPageOfMessages(client.getId(), page);
		return new LinkedMessagePage(page, pageMessages.getMessages(), pageMessages.getIsEnd());
	}


	private void applyLinkedTaskIds(Long clientId, List<Message> messages) {
		if (clientId == null || messages == null || messages.isEmpty()) {
			return;
		}
		List<Long> messageIds = messages.stream()
				.map(Message::getId)
				.filter(Objects::nonNull)
				.toList();
		if (messageIds.isEmpty()) {
			return;
		}
		Map<Long, Long> taskIdByMessageId = new HashMap<>();
		clientsRepository.findLinkedTaskIdsByClientIdAndMessageIds(clientId, messageIds)
				.forEach(row -> {
					if (row.length < 2 || row[0] == null || row[1] == null) {
						return;
					}
					Long messageId = ((Number) row[0]).longValue();
					Long taskId = ((Number) row[1]).longValue();
					taskIdByMessageId.put(messageId, taskId);
				});
		messages.forEach(message -> {
			Long taskId = taskIdByMessageId.get(message.getId());
			if (taskId != null) {
				message.setLinkedTaskId(taskId);
			}
		});
	}


	public List<Message> searchMessages(Long clientId, MessageText messageText) {
		if (clientId == null || messageText == null || messageText.getText() == null || messageText.getText().isBlank()) {
			return Collections.emptyList();
		}
		Client client = getClientForCurrentUser(clientId);
		User currentUser = userService.getCurrentUser();
		boolean observer = userService.isObserver(currentUser);
		String query = messageText.getText().toLowerCase(Locale.ROOT);
		return safeCollection(client.getMessages()).stream()
				.filter(message -> !observer || !isHiddenFromObserver(message))
				.filter(message -> message.getText() != null)
				.filter(message -> message.getText().toLowerCase(Locale.ROOT).contains(query))
				.sorted(Comparator.reverseOrder())
				.toList();
	}


	public List<Message> searchTaskMessage(Long taskId, MessageText messageText) {
		if (taskId == null || messageText == null || messageText.getText() == null || messageText.getText().isBlank()) {
			return Collections.emptyList();
		}
		getClientByTaskForCurrentUser(taskId);
		Task task = taskRepository.findById(taskId).orElseThrow();
		String query = messageText.getText().toLowerCase(Locale.ROOT);
		return safeCollection(task.getMessages()).stream()
				.filter(message -> message.getText() != null)
				.filter(message -> message.getText().toLowerCase(Locale.ROOT).contains(query))
				.sorted(Comparator.reverseOrder())
				.toList();
	}


	public void markMessageRead(Long taskId, UserId userId) {
		if (taskId == null || userId == null || userId.getUserId() == null) {
			logger.warn("mark task message read skipped: taskId or userId is null");
			return;
		}
		getClientByTaskForCurrentUser(taskId);
		Task task = taskRepository.findById(taskId).orElseThrow();
		User currentUser = userService.getCurrentUser();
		if (!userService.isAdmin(currentUser) && !Objects.equals(currentUser.getId(), userId.getUserId())) {
			throw new org.springframework.security.access.AccessDeniedException("Нельзя изменить отметку упоминания другого пользователя");
		}
		User user = userRepository.findById(userId.getUserId()).orElseThrow();
		if (task.getUnreadPingTasksMessages() != null) {
			task.getUnreadPingTasksMessages().put(user.getId(), false);
		}
		taskRepository.save(task);
	}


	public List<FileDto> getClientFiles(Long clientId) {
		if (clientId == null) {
			return Collections.emptyList();
		}
		Client client = getClientForCurrentUser(clientId);
		boolean observer = userService.isObserver(userService.getCurrentUser());
		return safeCollection(client.getMessages()).stream()
				.filter(m -> m.getFileUuid() != null)
				.filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
				.filter(m -> !observer || !Boolean.TRUE.equals(m.getIsComment()))
				.sorted(Comparator
						.comparing(Message::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
						.thenComparing(Message::getId, Comparator.nullsLast(Comparator.reverseOrder()))
				)
				.map(m -> new FileDto(
						m.getFileUuid(),
						m.getFileName(),
						m.getFileType(),
						m.getDate()
				))
				.toList();
	}


	public List<FileDto> getTaskFiles(Long clientId, Long taskId) {
		if (clientId == null || taskId == null) {
			return Collections.emptyList();
		}
		getClientByTaskForCurrentUser(taskId);
		// Файлы заявки — вложения её внутреннего чата, менеджеру организации (OBSERVER) они не отдаются.
		if (!userService.canUserSeeTaskChat(userService.getCurrentUser())) {
			return Collections.emptyList();
		}
		Task task = taskRepository.findById(taskId).orElseThrow();
		return safeCollection(task.getMessages()).stream()
				.filter(m -> m.getFileUuid() != null)
				.filter(m -> !Boolean.TRUE.equals(m.getDeleted()))
				.sorted(Comparator
						.comparing(Message::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
						.thenComparing(Message::getId, Comparator.nullsLast(Comparator.reverseOrder()))
				)
				.map(m -> new FileDto(
						m.getFileUuid(),
						m.getFileName(),
						m.getFileType(),
						m.getDate()
				))
				.toList();
	}


	private void unlinkMessageFromOtherTasks(
			Client client,
			Long selectedTaskId,
			Long linkedMessageId,
			Message linkedMessage
	) {
		if (client == null || client.getId() == null || linkedMessageId == null) {
			return;
		}

		for (Task previousTask : taskRepository.findAllByClientIdAndLinkedMessageId(client.getId(), linkedMessageId)) {
			if (previousTask == null || Objects.equals(previousTask.getId(), selectedTaskId)) {
				continue;
			}

			previousTask.setLinkedMessageId(null);
			Task savedPreviousTask = taskRepository.saveAndFlush(previousTask);
			eventPublisher.publish(TriggerType.TASK_UPDATED, eventPayload(
					"task", savedPreviousTask,
					"client", client,
					"message", linkedMessage,
					"oldLinkedMessageId", linkedMessageId,
					"newLinkedMessageId", null,
					"changes", List.of(Map.of(
							"field", "linkedMessageId",
							"label", "Связанное сообщение",
							"oldValue", describeLinkedMessage(linkedMessageId, linkedMessage),
							"newValue", describeLinkedMessage(null, null)
					))
			));
			globalSearchService.indexTask(client, savedPreviousTask);
		}
	}


	private String describeLinkedMessage(Long messageId, Message message) {
		if (messageId == null) {
			return "Не привязано";
		}
		String text = message == null ? "" : Objects.toString(message.getText(), "");
		text = text.replaceAll("\\s+", " ").trim();
		if (text.length() > 80) {
			text = text.substring(0, 77) + "...";
		}
		return text.isBlank()
				? "Сообщение №" + messageId
				: "Сообщение №%d «%s»".formatted(messageId, text);
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


	private static <T> Collection<T> safeCollection(Collection<T> collection) {
		return collection == null ? Collections.emptyList() : collection;
	}

	@Transactional
	public Map<String, Object> answerRequired(Long clientId, Long messageId, AnswerRequiredRequest request) {
		if (clientId == null || messageId == null || request == null) {
			throw new IllegalArgumentException("clientId, messageId and request must not be null");
		}
		Client client = getClientForCurrentUser(clientId);
		AnswerRequired answerRequired = Objects.requireNonNullElse(
				request.getAnswerRequired(),
				AnswerRequired.NOT_SET
		);
		return updateAnswerRequired(client, messageId, answerRequired);
	}


	@Transactional
	public Map<String, Object> answerRequiredForSystem(Long clientId, Long messageId, AnswerRequired answerRequired) {
		if (clientId == null || messageId == null) {
			throw new IllegalArgumentException("clientId and messageId must not be null");
		}
		Client client = clientsRepository.findById(clientId)
				.orElseThrow(() -> new NoSuchElementException("Client not found"));
		return updateAnswerRequired(
				client,
				messageId,
				Objects.requireNonNullElse(answerRequired, AnswerRequired.NOT_SET)
		);
	}


	/**
	 * Автоматическая метка «требует ответа» без сброса таймера: если в текущей цепочке входящих сообщений
	 * (после последнего ответа оператора или последнего «не требует ответа») уже есть сообщение с меткой
	 * «требует ответа», метка остаётся на нём, а новое сообщение не помечается. Если цепочку прервало
	 * «не требует ответа», новое сообщение помечается после него — таймер начинается с нового сообщения,
	 * а отметка «не требует ответа» сохраняется.
	 *
	 * @return id ранее помеченного сообщения цепочки, если метка не перенесена; empty — метка поставлена
	 */
	@Transactional
	public Optional<Long> markAnswerRequiredForSystem(Long clientId, Long messageId) {
		if (clientId == null || messageId == null) {
			throw new IllegalArgumentException("clientId and messageId must not be null");
		}
		Client client = clientsRepository.findById(clientId)
				.orElseThrow(() -> new NoSuchElementException("Client not found"));
		List<Message> conversationMessages = conversationMessages(client);
		for (int i = conversationMessages.size() - 1; i >= 0; i--) {
			if (Objects.equals(conversationMessages.get(i).getId(), messageId)) {
				for (int j = i - 1; j >= 0; j--) {
					Message previous = conversationMessages.get(j);
					if (isOutgoingOperatorMessage(previous)
							|| (isIncomingMessage(previous) && previous.getAnswerRequired() == AnswerRequired.ANSWER_NOT_REQUIRED)) {
						break;
					}
					if (isIncomingMessage(previous) && previous.getAnswerRequired() == AnswerRequired.ANSWER_REQUIRED) {
						return Optional.of(previous.getId());
					}
				}
				break;
			}
		}
		updateAnswerRequired(client, messageId, AnswerRequired.ANSWER_REQUIRED, false);
		return Optional.empty();
	}


	private List<Message> conversationMessages(Client client) {
		return safeCollection(client.getMessages()).stream()
				.filter(message -> message.getDate() != null)
				.filter(message -> !Boolean.TRUE.equals(message.getDeleted()))
				.filter(message -> !Boolean.TRUE.equals(message.getIsComment()))
				.sorted()
				.toList();
	}


	private Map<String, Object> updateAnswerRequired(Client client, Long messageId, AnswerRequired answerRequired) {
		return updateAnswerRequired(client, messageId, answerRequired, true);
	}


	/**
	 * @param resetChain true — снять отметки со всех входящих сообщений цепочки (ручная установка и обычные
	 *                   автоматизации: в цепочке остаётся одна отметка); false — изменить только выбранное сообщение
	 */
	private Map<String, Object> updateAnswerRequired(
			Client client,
			Long messageId,
			AnswerRequired answerRequired,
			boolean resetChain
	) {
		List<Message> conversationMessages = conversationMessages(client);
		int selectedIndex = -1;
		for (int i = 0; i < conversationMessages.size(); i++) {
			if (Objects.equals(conversationMessages.get(i).getId(), messageId)) {
				selectedIndex = i;
				break;
			}
		}
		if (selectedIndex < 0) {
			throw new NoSuchElementException("Message not found in client messages");
		}
		Message selectedMessage = conversationMessages.get(selectedIndex);
		if (!isIncomingMessage(selectedMessage)) {
			throw new IllegalArgumentException("Answer required can be changed only for incoming client message");
		}
		int startIndex = 0;
		for (int i = selectedIndex - 1; i >= 0; i--) {
			if (isOutgoingOperatorMessage(conversationMessages.get(i))) {
				startIndex = i + 1;
				break;
			}
		}
		int endIndex = conversationMessages.size() - 1;
		for (int i = selectedIndex + 1; i < conversationMessages.size(); i++) {
			if (isOutgoingOperatorMessage(conversationMessages.get(i))) {
				endIndex = i - 1;
				break;
			}
		}
		List<Message> groupMessages = new ArrayList<>();
		if (resetChain) {
			for (int i = startIndex; i <= endIndex; i++) {
				Message message = conversationMessages.get(i);
				if (isIncomingMessage(message)) {
					message.setAnswerRequired(AnswerRequired.NOT_SET);
					groupMessages.add(message);
				}
			}
		} else {
			groupMessages.add(selectedMessage);
		}
		selectedMessage.setAnswerRequired(answerRequired);
		messageRepository.saveAll(groupMessages);
		Message firstUnansweredMessage = getFirstUnansweredMessage(client.getMessages());
		ZonedDateTime firstUnansweredMessageDate = firstUnansweredMessage == null ? null : firstUnansweredMessage.getDate();
		Long firstUnansweredMessageId = firstUnansweredMessage == null ? null : firstUnansweredMessage.getId();

		client.setFirstUnansweredMessageDate(firstUnansweredMessageDate);
		client.setFirstUnansweredMessageId(firstUnansweredMessageId);
		Client savedClient = clientsRepository.save(client);
		globalSearchService.indexClient(savedClient);

		// Ручное изменение и автоматизация используют один и тот же realtime-контракт.
		// Сбрасываемые сообщения группы тоже отправляем, иначе у других открытых
		// клиентов останется старый ANSWER_REQUIRED до ручного обновления.
		groupMessages.forEach(message -> webSocketService.editedMessage(new ClientMessage(savedClient, message)));

		Map<String, Object> response = new HashMap<>();
		response.put("messageId", selectedMessage.getId());
		response.put("firstUnansweredMessageId", firstUnansweredMessageId);
		response.put("firstUnansweredMessageDate", firstUnansweredMessageDate);
		response.put("answerRequired", selectedMessage.getAnswerRequired());
		return response;
	}


	private record UserActionWaiter(
			Client client,
			User user,
			Map<Long, Set<User>> usersByClient,
			Logger logger,
			long sleep,
			EventPublisher eventPublisher,
			String key
	) implements Runnable {
		@Override
		public void run() {
			try {
				Thread.sleep(sleep);

				Set<User> users = usersByClient.get(client.getId());
				if (users != null) {
					boolean removed = users.remove(user);

					if (removed) {
						eventPublisher.publish(TriggerType.USER_CLOSED_DIALOG, eventPayload(
								"client", client,
								"user", user
						));
					}
				}
			} catch (InterruptedException ignored) {
			}
		}
	}

	private ZonedDateTime getFirstUnansweredMessageDate(Collection<Message> messages) {
		Message message = getFirstUnansweredMessage(messages);
		return message == null ? null : message.getDate();
	}

	private Message getFirstUnansweredMessage(Collection<Message> messages) {
		List<Message> sortedMessages = safeCollection(messages).stream()
				.filter(message -> message.getDate() != null)
				.filter(message -> !Boolean.TRUE.equals(message.getDeleted()))
				.sorted()
				.toList();
		ZonedDateTime lastOperatorAnswerDate = sortedMessages.stream()
				.filter(this::isOutgoingOperatorMessage)
				.map(Message::getDate)
				.max(ZonedDateTime::compareTo)
				.orElse(null);
		List<Message> unansweredIncomingMessages = sortedMessages.stream()
				.filter(this::isIncomingMessage)
				.filter(message -> lastOperatorAnswerDate == null || message.getDate().isAfter(lastOperatorAnswerDate))
				.toList();
		if (unansweredIncomingMessages.isEmpty()) {
			return null;
		}
		Message lastMarkedMessage = unansweredIncomingMessages.stream()
				.filter(message ->
						message.getAnswerRequired() == AnswerRequired.ANSWER_REQUIRED ||
								message.getAnswerRequired() == AnswerRequired.ANSWER_NOT_REQUIRED
				)
				.reduce((first, second) -> second)
				.orElse(null);
		if (lastMarkedMessage == null || lastMarkedMessage.getAnswerRequired() != AnswerRequired.ANSWER_REQUIRED) {
			return null;
		}
		return lastMarkedMessage;
	}

	private boolean isIncomingMessage(Message message) {
		return Boolean.FALSE.equals(message.getIsSent()) && !Boolean.TRUE.equals(message.getIsComment());
	}

	private boolean isOutgoingOperatorMessage(Message message) {
		return Boolean.TRUE.equals(message.getIsSent()) && !Boolean.TRUE.equals(message.getIsComment());
	}


	private void publishSlaPauseHistory(Sla sla, boolean paused, String reason) {
		if (sla == null || sla.getId() == null) {
			return;
		}
		Task task = taskRepository.findBySlaId(sla.getId()).orElse(null);
		if (task == null) {
			return;
		}
		Client client = clientsRepository.findByTaskId(task.getId()).orElse(null);
		List<Map<String, Object>> changes = new ArrayList<>();
		changes.add(Map.of(
				"field", "slaPause",
				"label", "SLA-пауза",
				"oldValue", paused ? "Снята с паузы" : "Поставлена на паузу",
				"newValue", paused ? "Поставлена на паузу" : "Снята с паузы"
		));
		if (reason != null && !reason.isBlank()) {
			changes.add(Map.of(
					"field", "slaPauseReason",
					"label", "Причина паузы",
					"oldValue", "—",
					"newValue", reason
			));
		}
		eventPublisher.publish(TriggerType.TASK_UPDATED, eventPayload(
				"task", task,
				"client", client,
				"changes", changes
		));
		if (client != null) {
			globalSearchService.indexClient(client);
			globalSearchService.indexTask(client, task);
		}
	}


	@Transactional
	public Message editTaskMessage(Long clientId, Long taskId, Long messageId, @NotNull Message payload) {
		if (clientId == null || taskId == null || messageId == null) {
			throw new IllegalArgumentException("clientId, taskId and messageId must not be null");
		}
		Task task = taskRepository.findById(taskId).orElseThrow();
		Client client = getClientByTaskForCurrentUser(taskId);
		if (!Objects.equals(client.getId(), clientId)) {
			throw new IllegalArgumentException("task does not belong to client");
		}
		Message message = safeCollection(task.getMessages()).stream()
				.filter(m -> Objects.equals(m.getId(), messageId))
				.findFirst()
				.orElseThrow();
		if (Boolean.TRUE.equals(message.getDeleted())) {
			throw new IllegalStateException("deleted message cannot be edited");
		}
		String newText = Objects.toString(payload.getText(), "").trim();
		if (newText.isBlank()) {
			throw new IllegalArgumentException("message text must not be blank");
		}
		message.setText(newText);
		message.setEditedAt(ZonedDateTime.now());
		Message savedMessage = messageRepository.saveAndFlush(message);
		safeCollection(task.getMessages()).stream()
				.filter(m -> Objects.equals(m.getId(), savedMessage.getId()))
				.findFirst()
				.ifPresent(m -> {
					m.setText(savedMessage.getText());
					m.setEditedAt(savedMessage.getEditedAt());
				});
		Task savedTask = taskRepository.saveAndFlush(task);
		globalSearchService.indexTask(client, savedTask);
		globalSearchService.indexTaskMessage(client, savedTask, savedMessage);
		eventPublisher.publish(
				TriggerType.MESSAGE_EDITED,
				eventPayload(
						"client", client,
						"task", savedTask,
						"message", savedMessage
				)
		);
		return savedMessage;
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
		Task task = taskRepository.findById(taskId).orElseThrow();
		userService.assertCurrentUserCanAccessTask(client, task);
		return client;
	}


	private Map<Long, Message> buildMessageById(List<Message> messages) {
		Map<Long, Message> messageById = new HashMap<>();
		for (Message message : safeCollection(messages)) {
			if (message != null && message.getId() != null) {
				messageById.put(message.getId(), message);
			}
		}
		return messageById;
	}

	/**
	 * @param hideFromObserver ответ на комментарий или удалённое сообщение показывается менеджеру организации
	 *                         без текста и вложения цитаты
	 */
	private void fillReplyDataForPage(List<Message> messages, boolean hideFromObserver) {
		if (messages == null || messages.isEmpty()) {
			return;
		}

		List<Long> replyMessageIds = messages.stream()
				.map(Message::getReplyMessageId)
				.filter(Objects::nonNull)
				.distinct()
				.toList();

		if (replyMessageIds.isEmpty()) {
			return;
		}

		Map<Long, Message> replyMessageById = new HashMap<>();
		Set<Long> hiddenReplyIds = new HashSet<>();

		messageRepository.findAllById(replyMessageIds).forEach(message -> {
			if (message == null || message.getId() == null) {
				return;
			}
			if (hideFromObserver && isHiddenFromObserver(message)) {
				hiddenReplyIds.add(message.getId());
			} else {
				replyMessageById.put(message.getId(), message);
			}
		});

		messages.forEach(message -> {
			if (hiddenReplyIds.contains(message.getReplyMessageId())) {
				// Страница читается в readOnly-транзакции: изменения сущности в БД не попадут
				message.setReplyMessageText("");
				message.setReplyUuid(null);
				message.setReplyFileType(null);
				return;
			}
			fillReplyMessageText(message, replyMessageById);
		});
	}


	private void fillReplyMessageText(Message message, Map<Long, Message> messageById) {
		if (message == null || message.getReplyMessageId() == null) {
			return;
		}
		Message replyMessage = messageById.get(message.getReplyMessageId());
		if (replyMessage == null) {
			message.setReplyMessageText("");
			return;
		}
		message.setReplyMessageText(Objects.toString(replyMessage.getText(), ""));
		if (message.getReplyFileType() == null) {
			message.setReplyFileType(replyMessage.getFileType());
		}
		if (message.getReplyUuid() == null && replyMessage.getFileUuid() != null && !replyMessage.getFileUuid().isBlank()) {
			try {
				message.setReplyUuid(UUID.fromString(replyMessage.getFileUuid()));
			} catch (Exception ignored) {
			}
		}
	}

}
