package ru.ravel.ItDesk.service;

import com.pengrad.telegrambot.TelegramException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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

	private final ClientRepository clientsRepository;
	private final TaskRepository taskRepository;
	private final MessageRepository messageRepository;
	private final TelegramService telegramService;
	private final UserService userService;
	private final OrganizationService organizationService;
	private final EmailService emailService;
	private final SlaRepository slaRepository;
	private final WhatsappService whatsappService;
	private final WebSocketService webSocketService;
	private final EventPublisher eventPublisher;
	private final UserRepository userRepository;
	private final GlobalSearchService globalSearchService;

	private final Map<String, ExecuteFuture> clientUserMapTypingExecutorServices = new ConcurrentHashMap<>();
	private final Map<String, ExecuteFuture> clientUserMapWatchingExecutorServices = new ConcurrentHashMap<>();
	private final Map<Long, Set<User>> typingUsers = new ConcurrentHashMap<>();
	private final Map<Long, Set<User>> watchingUsers = new ConcurrentHashMap<>();

	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final Integer pageLimit = 100;
	private final UserNotificationService userNotificationService;

	@Value("${app.is-demo:false}")
	private boolean isDemo;


	public List<Client> getClients() {
		return prepareClientsForList(userService.filterClientsByCurrentUser(clientsRepository.findAll()));
	}


	public List<Client> getClientsForSystem() {
		return prepareClientsForList(clientsRepository.findAll());
	}


	private List<Client> prepareClientsForList(List<Client> clients) {
		clients.forEach(client -> {
			Collection<Message> messages = safeCollection(client.getMessages());
			client.setLastMessage(messages.stream()
					.sorted()
					.reduce((first, second) -> second)
					.orElse(null));
			client.setUnreadMessagesCount(messages.stream()
					.filter(message -> Boolean.FALSE.equals(message.getIsRead()))
					.filter(message -> Boolean.FALSE.equals(message.getIsSent()))
					.filter(message -> !Boolean.TRUE.equals(message.getIsComment()))
					.filter(message -> !Boolean.TRUE.equals(message.getDeleted()))
					.count());
			client.setFirstUnansweredMessageDate(getFirstUnansweredMessageDate(messages));
			client.setTypingUsers(Objects.requireNonNullElse(typingUsers.get(client.getId()), Collections.emptySet()));
			client.setWatchingUsers(Objects.requireNonNullElse(watchingUsers.get(client.getId()), Collections.emptySet()));
			safeCollection(client.getTasks()).forEach(task -> {
				if (task.getMessages() != null) {
					task.getMessages().sort(Message::compareTo);
				}
			});
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
			}
		});
		return clients;
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
		if (sla == null) {
			return Duration.ZERO;
		}
		ZonedDateTime now = ZonedDateTime.now();
		long seconds = safeCollection(sla.getPauses()).stream()
				.mapToLong(p -> {
					ZonedDateTime end = (p.getEndedAt() == null) ? now : p.getEndedAt();
					return Math.max(0, Duration.between(p.getStartedAt(), end).getSeconds());
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
		if (deadline == null) return Duration.ZERO;
		return Duration.between(ZonedDateTime.now(), deadline);
	}


	public boolean isPaused(Sla sla) {
		return sla != null && safeCollection(sla.getPauses()).stream().anyMatch(p -> p.getEndedAt() == null);
	}


	public Message sendMessage(Long clientId, Message message) {
		if (message == null) {
			throw new IllegalArgumentException("message must not be null");
		}
		return sendMessageWithUser(clientId, message, userService.getCurrentUser());
	}


	public Message sendMessageWithUser(Long clientId, @NotNull Message message, User user) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		message.setDate(ZonedDateTime.now());
		message.setUser(user);
		message.setIsSent(true);
		message.setIsRead(true);
		message.setIsComment(Boolean.TRUE.equals(message.getIsComment()));
		Client client = getClientForCurrentUser(clientId);
		hydrateReplyData(message, client.getMessages());
		Message savedMessage = messageRepository.saveAndFlush(message);
		hydrateReplyData(savedMessage, client.getMessages());
		if (!Boolean.TRUE.equals(message.getIsComment()) && !isDemo) {
			try {
				if (client.getMessageFrom() == null) {
					logger.warn("message external delivery skipped: client messageFrom is null, clientId={}", client.getId());
				} else {
					switch (client.getMessageFrom()) {
						case TELEGRAM -> telegramService.sendMessage(client, savedMessage);
						case EMAIL -> emailService.sendEmail(savedMessage, client);
						case WHATSAPP -> whatsappService.sendMessage(savedMessage, client);
					}
				}
			} catch (Exception e) {
				logger.error(
						"message external delivery failed, but local message was saved: clientId={}, messageId={}",
						client.getId(),
						savedMessage.getId(),
						e
				);
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
						.filter(u -> ("%s %s".formatted(u.getLastname(), u.getFirstname())).equals(fullName))
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
	public Client markReadAndReturnClient(ClientUser clientUser) {
		if (clientUser == null || clientUser.getClientId() == null || clientUser.getUserId() == null) {
			logger.warn("mark-read skipped: clientId or userId is null, payload={}", clientUser);
			return null;
		}
		Optional<Client> clientOpt = clientsRepository.findById(clientUser.getClientId());
		if (clientOpt.isEmpty()) {
			logger.warn("mark-read skipped: client not found, clientId={}", clientUser.getClientId());
			return null;
		}
		Optional<User> userOpt = userRepository.findById(clientUser.getUserId());
		if (userOpt.isEmpty()) {
			logger.warn("mark-read skipped: user not found, userId={}", clientUser.getUserId());
			return null;
		}
		Client client = clientOpt.get();
		User user = userOpt.get();
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
		client.setFirstname(Objects.toString(c.get("firstname"), null));
		client.setLastname(Objects.toString(c.get("lastname"), null));
		Object organizationName = c.get("organization");
		client.setOrganization(safeCollection(organizationService.getOrganizations()).stream()
				.filter(it -> Objects.equals(it.getName(), organizationName))
				.findFirst().orElse(null));
		client.setMoreInfo(Objects.toString(c.get("moreInfo"), null));
		clientsRepository.save(client);
		globalSearchService.indexClient(client);
		eventPublisher.publish(TriggerType.CLIENT_UPDATED, eventPayload("client", client));
		return client;
	}


	public void typing(@NotNull ClientUserText clientUserText) {
		if (clientUserText == null || clientUserText.getClientId() == null || clientUserText.getUserId() == null) {
			logger.warn("typing skipped: clientId or userId is null, payload={}", clientUserText);
			return;
		}
		Client client = getClientForCurrentUser(clientUserText.getClientId());
		User user = userRepository.findById(clientUserText.getUserId()).orElseThrow();
		if (client.getTypingMessageText() == null) {
			client.setTypingMessageText(new HashMap<>());
		}
		client.getTypingMessageText().put(clientUserText.getUserId(), Objects.toString(clientUserText.getText(), ""));
		clientsRepository.save(client);
		String typingKey = "%d:%d".formatted(client.getId(), clientUserText.getUserId());
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
		getClientByTaskForCurrentUser(messageTask.getTask().getId());
		Task task = taskRepository.findById(messageTask.getTask().getId()).orElseThrow();
		task.setLinkedMessageId(messageTask.getMessage().getId());
		taskRepository.save(task);
		return messageTask;
	}


	public boolean deleteMessage(Long clientId, Long messageId) throws IllegalArgumentException {
		if (clientId == null || messageId == null) {
			throw new IllegalArgumentException("clientId and messageId must not be null");
		}
		Client client = getClientForCurrentUser(clientId);
		Message message = messageRepository.findById(messageId).orElseThrow();
		if (client.getMessageFrom() != null) {
			switch (client.getMessageFrom()) {
				case TELEGRAM -> {
					try {
						telegramService.deleteMessage(client, message);
					} catch (TelegramException e) {
						return false;
					}
				}
				case WHATSAPP, EMAIL -> {
				}
			}
		}
		message.setDeleted(true);
		messageRepository.save(message);
		globalSearchService.deleteDocument("CLIENT_MESSAGE:" + message.getId());
		globalSearchService.deleteDocument("TASK_MESSAGE:" + message.getId());
		eventPublisher.publish(TriggerType.MESSAGE_DELETED, eventPayload("client", client, "message", message));
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
					case EMAIL, WHATSAPP -> {
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
				.peek(client -> safeCollection(client.getTasks()).forEach(task -> task.setMessages(Collections.emptyList())))
				.toList();
	}


	@Transactional
	public Message addTaskMessage(Long taskId, @NotNull Message message) {
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		Client client = getClientByTaskForCurrentUser(taskId);
		message.setDate(ZonedDateTime.now());
		message.setUser(userService.getCurrentUser());
		message.setIsSent(Boolean.TRUE.equals(message.getIsSent()));
		message.setIsRead(Boolean.TRUE.equals(message.getIsRead()));
		message.setIsComment(Boolean.TRUE.equals(message.getIsComment()));
		Message savedMessage = messageRepository.saveAndFlush(message);
		Task task = taskRepository.findById(taskId).orElseThrow();
		if (task.getMessages() == null) {
			task.setMessages(new ArrayList<>());
		}
		task.getMessages().add(savedMessage);
		task.setLastActivity(savedMessage.getDate());

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
					.filter(u -> ("%s %s".formatted(u.getLastname(), u.getFirstname())).equals(fullName))
					.findFirst()
					.ifPresent(u -> {
						task.getUnreadPingTasksMessages().put(u.getId(), true);
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
		client = clientsRepository.findByTaskId(taskId).orElse(client);
		globalSearchService.indexTask(client, savedTask);
		globalSearchService.indexTaskMessage(client, savedTask, savedMessage);
		return savedMessage;
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


	public PageMessages getPageOfMessages(Long clientId, Integer page) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		int safePage = Math.max(1, Objects.requireNonNullElse(page, 1));
		Client client = getClientForCurrentUser(clientId);
		List<Message> clientMessages = safeCollection(client.getMessages()).stream().sorted().toList();
		int skipFromStart = Math.max(0, clientMessages.size() - pageLimit * safePage);
		long limit = skipFromStart != 0 ? pageLimit : Math.max(0, clientMessages.size() - ((long) pageLimit * (safePage - 1)));
		List<Message> messages = clientMessages.stream()
				.skip(skipFromStart)
				.limit(limit)
				.peek(message -> message.setReplyMessageText(clientMessages.stream()
						.filter(m -> Objects.equals(m.getId(), message.getReplyMessageId()))
						.findFirst().orElse(Message.builder().text("").build()).getText()))
				.toList();
		safeCollection(client.getTasks()).forEach(task -> messages.stream()
				.filter(message -> task.getLinkedMessageId() != null)
				.filter(message -> Objects.equals(message.getId(), task.getLinkedMessageId()))
				.forEach(message -> message.setLinkedTaskId(task.getLinkedMessageId())));
		return new PageMessages(messages, skipFromStart == 0);
	}


	public LinkedMessagePage getMessagesUntilLinkedMessage(Long clientId, Long linkedMessageId) {
		if (clientId == null || linkedMessageId == null) {
			throw new IllegalArgumentException("clientId and linkedMessageId must not be null");
		}
		Message message = messageRepository.findById(linkedMessageId).orElseThrow();
		Client client = getClientForCurrentUser(clientId);
		List<Message> messages = safeCollection(client.getMessages()).stream()
				.sorted()
				.peek(msg -> msg.setReplyMessageText(safeCollection(client.getMessages()).stream()
						.filter(m -> Objects.equals(m.getId(), msg.getReplyMessageId()))
						.findFirst().orElse(Message.builder().text("").build()).getText()))
				.toList();
		safeCollection(client.getTasks()).forEach(task -> messages.stream()
				.filter(msg -> Objects.equals(msg.getId(), task.getLinkedMessageId()))
				.forEach(msg -> msg.setLinkedTaskId(task.getLinkedMessageId())));
		int index = Math.max(0, messages.indexOf(message));
		int page = Math.max(1, ((Double) Math.ceil((double) (messages.size() - index) / pageLimit)).intValue());
		int skipFromStart = Math.max(0, messages.size() - pageLimit * page);
		return new LinkedMessagePage(page, messages.stream().skip(skipFromStart).sorted().toList(), skipFromStart == 0);
	}


	public List<Message> searchMessages(Long clientId, MessageText messageText) {
		if (clientId == null || messageText == null || messageText.getText() == null || messageText.getText().isBlank()) {
			return Collections.emptyList();
		}
		Client client = getClientForCurrentUser(clientId);
		String query = messageText.getText().toLowerCase(Locale.ROOT);
		return safeCollection(client.getMessages()).stream()
				.filter(message -> message.getText() != null)
				.filter(message -> message.getText().toLowerCase(Locale.ROOT).contains(query))
				.sorted()
				.toList();
	}


	public List<Message> searchTaskMessage(Long taskId, MessageText messageText) {
		if (taskId == null || messageText == null || messageText.getText() == null || messageText.getText().isBlank()) {
			return Collections.emptyList();
		}
		Task task = taskRepository.findById(taskId).orElseThrow();
		String query = messageText.getText().toLowerCase(Locale.ROOT);
		return safeCollection(task.getMessages()).stream()
				.filter(message -> message.getText() != null)
				.filter(message -> message.getText().toLowerCase(Locale.ROOT).contains(query))
				.sorted()
				.toList();
	}


	public void markMessageRead(Long taskId, UserId userId) {
		if (taskId == null || userId == null || userId.getUserId() == null) {
			logger.warn("mark task message read skipped: taskId or userId is null");
			return;
		}
		Task task = taskRepository.findById(taskId).orElseThrow();
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
		return safeCollection(client.getMessages()).stream()
				.filter(m -> m.getFileUuid() != null)
				.map(m -> new FileDto(m.getFileUuid(), m.getFileName(), m.getFileType()))
				.toList();
	}


	public List<FileDto> getTaskFiles(Long clientId, Long taskId) {
		if (clientId == null || taskId == null) {
			return Collections.emptyList();
		}
		Client client = getClientForCurrentUser(clientId);
		Task task = safeCollection(client.getTasks()).stream()
				.filter(t -> Objects.equals(t.getId(), taskId))
				.findFirst()
				.orElseThrow();
		return safeCollection(task.getMessages()).stream()
				.filter(m -> m.getFileUuid() != null)
				.map(m -> new FileDto(m.getFileUuid(), m.getFileName(), m.getFileType()))
				.toList();
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
		List<Message> conversationMessages = safeCollection(client.getMessages()).stream()
				.filter(message -> message.getDate() != null)
				.filter(message -> !Boolean.TRUE.equals(message.getDeleted()))
				.filter(message -> !Boolean.TRUE.equals(message.getIsComment()))
				.sorted()
				.toList();
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
		for (int i = startIndex; i <= endIndex; i++) {
			Message message = conversationMessages.get(i);
			if (isIncomingMessage(message)) {
				message.setAnswerRequired(AnswerRequired.NOT_SET);
				groupMessages.add(message);
			}
		}
		selectedMessage.setAnswerRequired(answerRequired);
		messageRepository.saveAll(groupMessages);
		ZonedDateTime firstUnansweredMessageDate = getFirstUnansweredMessageDate(client.getMessages());
		client.setFirstUnansweredMessageDate(firstUnansweredMessageDate);
		clientsRepository.save(client);
		globalSearchService.indexClient(client);
		Map<String, Object> response = new HashMap<>();
		response.put("firstUnansweredMessageDate", firstUnansweredMessageDate);
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
		return lastMarkedMessage.getDate();
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
		userService.assertCurrentUserCanAccessClient(client);
		return client;
	}

}