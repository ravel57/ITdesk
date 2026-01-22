package ru.ravel.ItDesk.service;

import com.pengrad.telegrambot.TelegramException;
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

	private final Map<ClientUserText, ExecuteFuture> clientUserMapTypingExecutorServices = new ConcurrentHashMap<>();
	private final Map<ClientUser, ExecuteFuture> clientUserMapWatchingExecutorServices = new ConcurrentHashMap<>();
	private final Map<Client, Set<User>> typingUsers = new ConcurrentHashMap<>();
	private final Map<Client, Set<User>> watchingUsers = new ConcurrentHashMap<>();

	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final Integer pageLimit = 100;

	@Value("${app.is-demo:false}")
	private boolean isDemo;


	public List<Client> getClients() {
		List<Client> clients = clientsRepository.findAll();
		clients.forEach(client -> {
			client.setLastMessage(client.getMessages().stream().sorted().skip(Math.max(0, client.getMessages().size() - 1)).findFirst().orElseThrow());
			client.setUnreadMessagesCount(client.getMessages().stream().filter(message -> !message.isRead()).count());
			client.setTypingUsers(Objects.requireNonNullElse(typingUsers.get(client), Collections.emptySet()));
			client.setWatchingUsers(Objects.requireNonNullElse(watchingUsers.get(client), Collections.emptySet()));
			client.getTasks().forEach(task -> task.getMessages().sort(Message::compareTo));
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
		Client client = clientsRepository.findById(clientId).orElseThrow();
		setSla(client, task);
		if (task.getMessages() != null && !task.getMessages().isEmpty()) {
			messageRepository.saveAll(task.getMessages());
		}
		task.setLastActivity(ZonedDateTime.now());
		taskRepository.save(task);
		client.getTasks().add(task);
		clientsRepository.save(client);
		eventPublisher.publish(TriggerType.TASK_CREATED, Map.of("task", task, "client", client));
		return task;
	}


	public Task updateTask(Long clientId, @NotNull Task task) {
		Task olderTask = taskRepository.findById(task.getId()).orElseThrow();
		Client client = clientsRepository.findById(clientId).orElseThrow();
		setSla(client, olderTask);
		FrozenStatus frozenStatus = FrozenStatus.getInstance();
		CompletedStatus completedStatus = CompletedStatus.getInstance();
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
		if (olderTask.getFrozen() != null && olderTask.getFrozen()) {
			if (!olderTask.getStatus().equals(frozenStatus) && !olderTask.getStatus().equals(completedStatus)) {
				olderTask.setPreviusStatus(olderTask.getStatus());
			}
			olderTask.setStatus(frozenStatus);
			olderTask.setFrozen(true);
		} else if (olderTask.getPreviusStatus() != null
				&& frozenStatus.getId().equals(olderTask.getStatus().getId())
				&& olderTask.getStatus().equals(frozenStatus)) {
			olderTask.setStatus(olderTask.getPreviusStatus());
		}
		if (olderTask.getCompleted() != null && olderTask.getCompleted()) {
			if (!olderTask.getStatus().equals(completedStatus) && !olderTask.getStatus().equals(frozenStatus)) {
				olderTask.setPreviusStatus(olderTask.getStatus());
			}
			if (Boolean.TRUE.equals(olderTask.getFrozen())) {
				olderTask.setFrozen(false);
			}
			olderTask.setStatus(completedStatus);
			olderTask.setCompleted(true);
		} else if (olderTask.getPreviusStatus() != null
				&& Boolean.FALSE.equals(olderTask.getFrozen())
				&& !olderTask.getPreviusStatus().equals(completedStatus)
				&& olderTask.getStatus().equals(completedStatus)) {
			olderTask.setStatus(olderTask.getPreviusStatus());
		} else if (olderTask.getStatus().equals(completedStatus)
				&& olderTask.getStatus().getId().equals(completedStatus.getId())) {
			olderTask.setCompleted(false);
			olderTask.setStatus(olderTask.getPreviusStatus());
		}
		olderTask.setLastActivity(ZonedDateTime.now());
		eventPublisher.publish(TriggerType.TASK_UPDATED, Map.of("task", task, "client", client));
		return taskRepository.save(olderTask);
	}


	private void setSla(@NotNull Client client, Task task) {
		Organization organization = client.getOrganization();
		Duration duration;
		if (organization != null) {
			duration = organizationService.getSlaByPriority().get(organization).get(task.getPriority());
		} else {
			duration = DefaultOrganization.getInstance().getSla().get(task.getPriority());
		}
		Sla sla = Sla.builder().startDate(task.getCreatedAt()).duration(duration).build();
		slaRepository.save(sla);
		task.setSla(sla);
	}


	public boolean sendMessage(Long clientId, Message message) {
		return sendMessageWithUser(clientId, message, userService.getCurrentUser());
	}


	public boolean sendMessageWithUser(Long clientId, @NotNull Message message, User user) {
		message.setDate(ZonedDateTime.now());
		message.setUser(user);
		message.setSent(true);
		messageRepository.save(message);
		Client client = clientsRepository.findById(clientId).orElseThrow();
		if (!message.isComment() && !isDemo) {
			try {
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
			Matcher matcher = pattern.matcher(message.getText());
			if (client.getUnreadPingMessages() == null) {
				client.setUnreadPingMessages(new HashMap<>());
			}
			List<User> users = userService.getUsers();
			while (matcher.find()) {
				String fullName = matcher.group(1);
				users.stream()
						.filter(u -> ("%s %s".formatted(u.getLastname(), u.getFirstname())).equals(fullName))
						.findFirst()
						.ifPresent(u -> client.getUnreadPingMessages().put(u.getId(), true));
			}
		}
		webSocketService.sendNewMessages(new ClientMessage(client, message));
		client.getMessages().add(message);
		clientsRepository.save(client);
		eventPublisher.publish(TriggerType.MESSAGE_OUTGOING, Map.of("message", message, "client", client));
		return true;
	}


	public Client markReadAndReturnClient(@NotNull ClientUser clientUser) {
		Client client = clientsRepository.findById(clientUser.getClient().getId()).orElseThrow();
		ExecuteFuture executeFuture = clientUserMapWatchingExecutorServices.get(clientUser);
		if (executeFuture != null) {
			clientUserMapWatchingExecutorServices.get(clientUser).getFuture().cancel(true);
		} else {
			executeFuture = new ExecuteFuture();
			clientUserMapWatchingExecutorServices.put(clientUser, executeFuture);
		}
		UserActionWaiter task = new UserActionWaiter(client, clientUser.getUser(), watchingUsers, logger, 30_000);
		executeFuture.setFuture(executeFuture.getExecutor().submit(task));

		if (client.getUnreadPingMessages() != null) {
			client.getUnreadPingMessages().put(clientUser.getUser().getId(), false);
		}
		clientsRepository.save(client);

		List<Message> messages = client.getMessages().stream()
				.filter(message -> !message.isRead())
				.peek(message -> message.setRead(true))
				.toList();
		messageRepository.saveAll(messages);
		return client;
	}


	public Client updateClient(Long clientId, @NotNull Map<String, Object> c) {    // FIXME
		Client client = clientsRepository.findById(clientId).orElseThrow();
		client.setFirstname(c.get("firstname").toString());
		client.setLastname(c.get("lastname").toString());
		client.setOrganization(organizationService.getOrganizations().stream()
				.filter(it -> it.getName().equals(c.get("organization")))
				.findFirst().orElse(null));
		client.setMoreInfo((String) c.get("moreInfo"));
		clientsRepository.save(client);
		eventPublisher.publish(TriggerType.CLIENT_UPDATED, Map.of("client", client));
		return client;
	}


	public void typing(@NotNull ClientUserText clientUserText) {
		Client client = clientsRepository.findById(clientUserText.getClient().getId()).orElseThrow();
		client.getTypingMessageText().put(clientUserText.getUser().getId(), clientUserText.getText());
		clientsRepository.save(client);
		ExecuteFuture executeFuture = clientUserMapTypingExecutorServices.get(clientUserText);
		if (executeFuture != null) {
			clientUserMapTypingExecutorServices.get(clientUserText).getFuture().cancel(true);
		} else {
			executeFuture = new ExecuteFuture();
			clientUserMapTypingExecutorServices.put(clientUserText, executeFuture);
		}
		UserActionWaiter task = new UserActionWaiter(client, clientUserText.getUser(), typingUsers, logger, 30_000);
		executeFuture.setFuture(executeFuture.getExecutor().submit(task));
	}


	public MessageTask linkToTask(@NotNull MessageTask messageTask) {
		Task task = taskRepository.findById(messageTask.getTask().getId()).orElseThrow();
		task.setLinkedMessageId(messageTask.getMessage().getId());
		taskRepository.save(task);
		return messageTask;
	}


	public boolean deleteMessage(Long clientId, Long messageId) {
		Client client = clientsRepository.findById(clientId).orElseThrow();
		Message message = messageRepository.findById(messageId).orElseThrow();
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
		message.setDeleted(true);
		messageRepository.save(message);
		eventPublisher.publish(TriggerType.MESSAGE_DELETED, Map.of("client", client, "message", message));
		return true;
	}


	public Boolean deleteClient(Long clientId) {
		Client client = clientsRepository.findById(clientId).orElseThrow();
		clientsRepository.delete(client);
		eventPublisher.publish(TriggerType.CLIENT_DELETED, Map.of("client", client));
		return true;
	}

	public List<Client> getClientsForObserver(User observer) {
		return getClients().stream()
				.filter(client -> observer.getAvailableOrganizations().contains(client.getOrganization()))
				.peek(client -> client.getTasks().forEach(task -> task.setMessages(Collections.emptyList())))
				.toList();
	}


	public boolean addTaskMessage(Long taskId, @NotNull Message message) {
		message.setDate(ZonedDateTime.now());
		message.setUser(userService.getCurrentUser());
		messageRepository.save(message);
		Task task = taskRepository.findById(taskId).orElseThrow();
		task.getMessages().add(message);
		taskRepository.save(task);

		// pings
		Pattern pattern = Pattern.compile("@\\[(.+?)]");
		Matcher matcher = pattern.matcher(message.getText());
		if (task.getUnreadPingTasksMessages() == null) {
			task.setUnreadPingTasksMessages(new HashMap<>());
		}
		List<User> users = userService.getUsers();
		while (matcher.find()) {
			String fullName = matcher.group(1);
			users.stream()
					.filter(u -> ("%s %s".formatted(u.getLastname(), u.getFirstname())).equals(fullName))
					.findFirst()
					.ifPresent(u -> task.getUnreadPingTasksMessages().put(u.getId(), true));
		}
		taskRepository.save(task);
		return true;
	}


	public PageMessages getPageOfMessages(Long clientId, Integer page) {
		Client client = clientsRepository.findById(clientId).orElseThrow();
		int skipFromStart = Math.max(0, client.getMessages().size() - pageLimit * page);
		List<Message> messages = client.getMessages().stream()
				.sorted()
				.skip(skipFromStart)
				.limit(skipFromStart != 0 ? pageLimit : client.getMessages().size() - ((long) pageLimit * (page - 1)))
				.peek(message -> message.setReplyMessageText(client.getMessages().stream()
						.filter(m -> m.getId().equals(message.getReplyMessageId()))
						.findFirst().orElse(Message.builder().text("").build()).getText()))
				.toList();
		client.getTasks().forEach(task -> messages.stream()
				.filter(message -> message.getId().equals(task.getLinkedMessageId()))
				.forEach(message -> message.setLinkedTaskId(task.getLinkedMessageId())));
		return new PageMessages(messages, skipFromStart == 0);
	}


	public LinkedMessagePage getMessagesUntilLinkedMessage(Long clientId, Long linkedMessageId) {
		Message message = messageRepository.findById(linkedMessageId).orElseThrow();
		Client client = clientsRepository.findById(clientId).orElseThrow();
		List<Message> messages = client.getMessages().stream()
				.sorted()
				.peek(msg -> msg.setReplyMessageText(client.getMessages().stream()
						.filter(m -> m.getId().equals(msg.getReplyMessageId()))
						.findFirst().orElse(Message.builder().text("").build()).getText()))
				.toList();
		client.getTasks().forEach(task -> messages.stream()
				.filter(msg -> msg.getId().equals(task.getLinkedMessageId()))
				.forEach(msg -> msg.setLinkedTaskId(task.getLinkedMessageId())));
		int index = messages.indexOf(message);
		int page = ((Double) Math.ceil((double) (messages.size() - index) / pageLimit)).intValue();
		int skipFromStart = Math.max(0, messages.size() - pageLimit * page);
		return new LinkedMessagePage(page, messages.stream().skip(skipFromStart).sorted().toList(), skipFromStart == 0);
	}


	public List<Message> searchMessages(Long clientId, MessageText messageText) {
		Client client = clientsRepository.findById(clientId).orElseThrow();
		return client.getMessages().stream()
				.filter(message -> message.getText() != null)
				.filter(message -> message.getText().toLowerCase().contains(messageText.getText().toLowerCase()))
				.sorted()
				.toList();
	}


	public List<Message> searchTaskMessage(Long taskId, MessageText messageText) {
		Task task = taskRepository.findById(taskId).orElseThrow();
		return task.getMessages().stream()
				.filter(message -> message.getText() != null)
				.filter(message -> message.getText().toLowerCase().contains(messageText.getText().toLowerCase()))
				.sorted()
				.toList();
	}


	public void markMessageRead(Long taskId, UserId userId) {
		Task task = taskRepository.findById(taskId).orElseThrow();
		User user = userRepository.findById(userId.getUserId()).orElseThrow();
		if (task.getUnreadPingTasksMessages() != null) {
			task.getUnreadPingTasksMessages().put(user.getId(), false);
		}
		taskRepository.save(task);
	}


	public List<FileDto> getClientFiles(Long clientId) {
		Client client = clientsRepository.findById(clientId).orElseThrow();
		return client.getMessages().stream()
				.filter(m -> m.getFileUuid() != null)
				.map(m -> new FileDto(m.getFileUuid(), m.getFileName(), m.getFileType()))
				.toList();
	}


	private record UserActionWaiter(
			Client client,
			User user,
			Map<Client, Set<User>> usersByClient,
			Logger logger,
			long sleep
	) implements Runnable {
		@Override
		public void run() {
			Set<User> users = usersByClient.get(client);
			if (users == null) {
				ConcurrentSkipListSet<User> listSet = new ConcurrentSkipListSet<>();
				listSet.add(user);
				usersByClient.put(client, listSet);
			} else {
				users.add(user);
			}
			try {
				Thread.sleep(sleep);
				usersByClient.get(client).remove(user);
			} catch (InterruptedException ignored) {
			}
		}
	}

}