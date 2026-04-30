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

	private final Map<String, ExecuteFuture> clientUserMapTypingExecutorServices = new ConcurrentHashMap<>();
	private final Map<String, ExecuteFuture> clientUserMapWatchingExecutorServices = new ConcurrentHashMap<>();
	private final Map<Long, Set<User>> typingUsers = new ConcurrentHashMap<>();
	private final Map<Long, Set<User>> watchingUsers = new ConcurrentHashMap<>();

	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final Integer pageLimit = 100;

	@Value("${app.is-demo:false}")
	private boolean isDemo;


	public List<Client> getClients() {
		List<Client> clients = clientsRepository.findAll();
		clients.forEach(client -> {
			Collection<Message> messages = safeCollection(client.getMessages());
			client.setLastMessage(messages.stream()
					.sorted()
					.reduce((first, second) -> second)
					.orElse(null));
			client.setUnreadMessagesCount(messages.stream()
					.filter(message -> Boolean.FALSE.equals(message.getIsRead()))
					.count());
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


	public Task newTask(Long clientId, @NotNull Task task) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		Client client = clientsRepository.findById(clientId).orElseThrow();
		setSla(client, task);
		if (task.getMessages() != null && !task.getMessages().isEmpty()) {
			messageRepository.saveAll(task.getMessages());
		}
		task.setLastActivity(ZonedDateTime.now());
		taskRepository.save(task);
		if (client.getTasks() == null) {
			client.setTasks(new ArrayList<>());
		}
		client.getTasks().add(task);
		clientsRepository.save(client);
		eventPublisher.publish(TriggerType.TASK_CREATED, eventPayload("task", task, "client", client));
		if (task.getExecutor() != null) {
			webSocketService.userNotification(new UserNotification(UserNotificationEvent.NEW_TASK, task.getName(), task.getExecutor().getId()));
		}
		return task;
	}


	public Task updateTask(Long clientId, @NotNull Task task) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		if (task.getId() == null) {
			throw new IllegalArgumentException("task.id must not be null");
		}
		Task olderTask = taskRepository.findById(task.getId()).orElseThrow();
		Client client = clientsRepository.findById(clientId).orElseThrow();
		setSla(client, olderTask);
		FrozenStatus frozenStatus = FrozenStatus.getInstance();
		CompletedStatus completedStatus = CompletedStatus.getInstance();
		Priority oldPriority = olderTask.getPriority();
		Status oldStatus = olderTask.getStatus();
		User oldExecutor = olderTask.getExecutor();
		Boolean oldCompleted = olderTask.getCompleted();
		ZonedDateTime oldDeadline = olderTask.getDeadline();
		Set<Tag> oldTags = new HashSet<>(Objects.requireNonNullElse(olderTask.getTags(), Collections.emptySet()));
		olderTask.setName(task.getName());
		olderTask.setDescription(task.getDescription());
		olderTask.setPriority(task.getPriority());
		olderTask.setStatus(task.getStatus());
		olderTask.setDeadline(task.getDeadline());
		olderTask.setExecutor(task.getExecutor());
		olderTask.setTags(task.getTags());
		olderTask.setLinkedMessageId(task.getLinkedMessageId());
		olderTask.setFrozen(task.getFrozen());
		olderTask.setCompleted(task.getCompleted());
		if (task.getPreviusStatus() != null) {
			olderTask.setPreviusStatus(task.getPreviusStatus());
		} else {
			olderTask.setPreviusStatus(completedStatus);
		}
		olderTask.setSla(task.getSla());
		if (Boolean.TRUE.equals(olderTask.getFrozen())) {
			if (!Objects.equals(olderTask.getStatus(), frozenStatus) && !Objects.equals(olderTask.getStatus(), completedStatus)) {
				olderTask.setPreviusStatus(olderTask.getStatus());
			}
			olderTask.setStatus(frozenStatus);
			olderTask.setFrozen(true);
		} else if (olderTask.getPreviusStatus() != null
				&& Objects.equals(frozenStatus.getId(), olderTask.getStatus() == null ? null : olderTask.getStatus().getId())
				&& Objects.equals(olderTask.getStatus(), frozenStatus)) {
			olderTask.setStatus(olderTask.getPreviusStatus());
		}
		if (Boolean.TRUE.equals(olderTask.getCompleted())) {
			if (!Objects.equals(olderTask.getStatus(), completedStatus) && !Objects.equals(olderTask.getStatus(), frozenStatus)) {
				olderTask.setPreviusStatus(olderTask.getStatus());
			}
			if (Boolean.TRUE.equals(olderTask.getFrozen())) {
				olderTask.setFrozen(false);
			}
			olderTask.setStatus(completedStatus);
			olderTask.setCompleted(true);
		} else if (olderTask.getPreviusStatus() != null
				&& Boolean.FALSE.equals(olderTask.getFrozen())
				&& !Objects.equals(olderTask.getPreviusStatus(), completedStatus)
				&& Objects.equals(olderTask.getStatus(), completedStatus)) {
			olderTask.setStatus(olderTask.getPreviusStatus());
		} else if (Objects.equals(olderTask.getStatus(), completedStatus)
				&& Objects.equals(olderTask.getStatus() == null ? null : olderTask.getStatus().getId(), completedStatus.getId())) {
			olderTask.setCompleted(false);
			olderTask.setStatus(olderTask.getPreviusStatus());
		}
		olderTask.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(olderTask);
		eventPublisher.publish(TriggerType.TASK_UPDATED, eventPayload(
				"task", savedTask,
				"client", client
		));
		if (!Objects.equals(oldStatus, savedTask.getStatus())) {
			eventPublisher.publish(TriggerType.TASK_STATUS_CHANGED, eventPayload(
					"task", savedTask,
					"client", client,
					"oldStatus", oldStatus,
					"newStatus", savedTask.getStatus()
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
				webSocketService.userNotification(new UserNotification(
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
			eventPublisher.publish(TriggerType.TASK_COMPLETED, eventPayload(
					"task", savedTask,
					"client", client
			));

			eventPublisher.publish(TriggerType.TASK_CLOSED, eventPayload(
					"task", savedTask,
					"client", client
			));
		}
		if (Boolean.TRUE.equals(oldCompleted) && Boolean.FALSE.equals(savedTask.getCompleted())) {
			eventPublisher.publish(TriggerType.TASK_REOPENED, eventPayload(
					"task", savedTask,
					"client", client
			));
		}
		Set<Tag> newTags = new HashSet<>(Objects.requireNonNullElse(savedTask.getTags(), Collections.emptySet()));
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


	private void setSla(@NotNull Client client, Task task) {
		if (task == null) {
			return;
		}
		Organization organization = client.getOrganization();
		SlaValue slaValue = null;
		if (organization != null) {
			Map<Priority, SlaValue> map = organizationService.getSlaByPriority().get(organization);
			if (map != null) slaValue = map.get(task.getPriority());
		}
		if (slaValue == null && DefaultOrganization.getInstance().getSla() != null) {
			slaValue = DefaultOrganization.getInstance().getSla().get(task.getPriority());
		}
		Duration duration = (slaValue == null) ? Duration.ZERO : slaValue.toDuration();
		Sla sla = Sla.builder()
				.startDate(Objects.requireNonNullElse(task.getCreatedAt(), ZonedDateTime.now()))
				.duration(duration)
				.build();
		slaRepository.save(sla);
		task.setSla(sla);
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
		active.setEndedAt(ZonedDateTime.now());
		slaRepository.save(sla);
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


	public boolean sendMessage(Long clientId, Message message) {
		if (message == null) {
			throw new IllegalArgumentException("message must not be null");
		}
		return sendMessageWithUser(clientId, message, userService.getCurrentUser());
	}


	public boolean sendMessageWithUser(Long clientId, @NotNull Message message, User user) {
		if (message == null) {
			throw new IllegalArgumentException("message must not be null");
		}
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		message.setDate(ZonedDateTime.now());
		message.setUser(user);
		message.setIsSent(true);
		message.setIsRead(Boolean.TRUE.equals(message.getIsRead()));
		message.setIsComment(Boolean.TRUE.equals(message.getIsComment()));
		messageRepository.save(message);
		Client client = clientsRepository.findById(clientId).orElseThrow();
		if (!Boolean.TRUE.equals(message.getIsComment()) && !isDemo) {
			try {
				if (client.getMessageFrom() == null) {
					logger.warn("message send skipped: client messageFrom is null, clientId={}", client.getId());
					return false;
				}
				switch (client.getMessageFrom()) {
					case TELEGRAM -> telegramService.sendMessage(client, message);
					case EMAIL -> emailService.sendEmail(message, client);
					case WHATSAPP -> whatsappService.sendMessage(message, client);
				}
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
				return false;
			}
		} else {
			// pings
			Pattern pattern = Pattern.compile("@\\[(.+?)]");
			Matcher matcher = pattern.matcher(Objects.requireNonNullElse(message.getText(), ""));
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
							eventPublisher.publish(TriggerType.MESSAGE_MENTIONED_USER, eventPayload("client", client, "message", message, "mentionedUser", u));
							webSocketService.userNotification(new UserNotification(UserNotificationEvent.MENTIONED_USER, Objects.toString(message.getText(), ""), u.getId()));
						});
			}
		}
		webSocketService.sendNewMessages(new ClientMessage(client, message));
		if (client.getMessages() == null) {
			client.setMessages(new ArrayList<>());
		}
		client.getMessages().add(message);
		clientsRepository.save(client);
		eventPublisher.publish(TriggerType.MESSAGE_OUTGOING, eventPayload("message", message, "client", client));
		return true;
	}


	@Transactional
	public Client markReadAndReturnClient(@NotNull ClientUser clientUser) {
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
				.filter(message -> !message.getIsRead())
				.filter(message -> !message.getIsSent())
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
		Client client = clientsRepository.findById(clientId).orElseThrow();
		client.setFirstname(Objects.toString(c.get("firstname"), null));
		client.setLastname(Objects.toString(c.get("lastname"), null));
		Object organizationName = c.get("organization");
		client.setOrganization(safeCollection(organizationService.getOrganizations()).stream()
				.filter(it -> Objects.equals(it.getName(), organizationName))
				.findFirst().orElse(null));
		client.setMoreInfo(Objects.toString(c.get("moreInfo"), null));
		clientsRepository.save(client);
		eventPublisher.publish(TriggerType.CLIENT_UPDATED, eventPayload("client", client));
		return client;
	}


	public void typing(@NotNull ClientUserText clientUserText) {
		if (clientUserText == null || clientUserText.getClientId() == null || clientUserText.getUserId() == null) {
			logger.warn("typing skipped: clientId or userId is null, payload={}", clientUserText);
			return;
		}
		Client client = clientsRepository.findById(clientUserText.getClientId()).orElseThrow();
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
		Task task = taskRepository.findById(messageTask.getTask().getId()).orElseThrow();
		task.setLinkedMessageId(messageTask.getMessage().getId());
		taskRepository.save(task);
		return messageTask;
	}


	public boolean deleteMessage(Long clientId, Long messageId) {
		if (clientId == null || messageId == null) {
			throw new IllegalArgumentException("clientId and messageId must not be null");
		}
		Client client = clientsRepository.findById(clientId).orElseThrow();
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
		eventPublisher.publish(TriggerType.MESSAGE_DELETED, eventPayload("client", client, "message", message));
		return true;
	}


	public Boolean deleteClient(Long clientId) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		Client client = clientsRepository.findById(clientId).orElseThrow();
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


	public boolean addTaskMessage(Long taskId, @NotNull Message message) {
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		message.setDate(ZonedDateTime.now());
		message.setUser(userService.getCurrentUser());
		message.setIsSent(Boolean.TRUE.equals(message.getIsSent()));
		message.setIsRead(Boolean.TRUE.equals(message.getIsRead()));
		message.setIsComment(Boolean.TRUE.equals(message.getIsComment()));
		messageRepository.save(message);
		Task task = taskRepository.findById(taskId).orElseThrow();
		if (task.getMessages() == null) {
			task.setMessages(new ArrayList<>());
		}
		task.getMessages().add(message);
		taskRepository.save(task);

		// pings
		Pattern pattern = Pattern.compile("@\\[(.+?)]");
		Matcher matcher = pattern.matcher(Objects.requireNonNullElse(message.getText(), ""));
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
						eventPublisher.publish(TriggerType.TASK_MESSAGE_MENTIONED_USER, eventPayload("task", task, "message", message, "mentionedUser", u));
						webSocketService.userNotification(new UserNotification(UserNotificationEvent.MENTIONED_USER_IN_TASK_CHAT, Objects.toString(task.getName(), ""), u.getId()));
					});
		}
		if (task.getExecutor() != null) {
			webSocketService.userNotification(new UserNotification(UserNotificationEvent.NEW_CHAT_MESSAGE, task.getName(), task.getExecutor().getId()));
		}
		taskRepository.save(task);
		return true;
	}


	public PageMessages getPageOfMessages(Long clientId, Integer page) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		int safePage = Math.max(1, Objects.requireNonNullElse(page, 1));
		Client client = clientsRepository.findById(clientId).orElseThrow();
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
		Client client = clientsRepository.findById(clientId).orElseThrow();
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
		Client client = clientsRepository.findById(clientId).orElseThrow();
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
		Client client = clientsRepository.findById(clientId).orElseThrow();
		return safeCollection(client.getMessages()).stream()
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

}