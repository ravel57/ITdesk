package ru.ravel.ItDesk.service;

import com.pengrad.telegrambot.TelegramException;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.ClientUser;
import ru.ravel.ItDesk.dto.ClientUserText;
import ru.ravel.ItDesk.dto.ExecuteFuture;
import ru.ravel.ItDesk.dto.MessageTask;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;


@Service
@RequiredArgsConstructor
public class ClientService {

	private final ClientRepository clientsRepository;
	private final TaskRepository taskRepository;
	private final MessageRepository messageRepository;
	private final TelegramService telegramService;
	private final UserService userService;
	private final OrganizationService organizationService;

	private final Map<ClientUserText, ExecuteFuture> clientUserMapTypingExecutorServices = new ConcurrentHashMap<>();
	private final Map<ClientUser, ExecuteFuture> clientUserMapWatchingExecutorServices = new ConcurrentHashMap<>();
	private final Map<Client, Set<User>> typingUsers = new ConcurrentHashMap<>();
	private final Map<Client, Set<User>> watchingUsers = new ConcurrentHashMap<>();

	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final EmailService emailService;


	public List<Client> getClients() {
		List<Client> clients = clientsRepository.findAll();
		clients.forEach(client -> {
			client.getMessages().sort(Message::compareTo);
			client.setTypingUsers(Objects.requireNonNullElse(typingUsers.get(client), Collections.emptySet()));
			client.setWatchingUsers(Objects.requireNonNullElse(watchingUsers.get(client), Collections.emptySet()));
			switch (client.getMessageFrom()) {
				case TELEGRAM -> client.setSourceChannel(Objects.requireNonNullElse(client.getTgBot().getName(), ""));
				case EMAIL -> client.setSourceChannel(Objects.requireNonNullElse(client.getEmailAccountSender().getName(), ""));    // FIXME
			}
		});
		return clients;
	}


	public Task newTask(Long clientId, @NotNull Task task) {
		Client client = clientsRepository.findById(clientId).orElseThrow();
		client.getTasks().add(task);
		taskRepository.save(task);
		clientsRepository.save(client);
		return task;
	}


	public Task updateTask(Task task) {
		return taskRepository.save(task);
	}


	public boolean sendMessage(Long clientId, @NotNull Message message) {
		message.setDate(ZonedDateTime.now());
		message.setUser(userService.getCurrentUser());
		messageRepository.save(message);
		Client client = clientsRepository.findById(clientId).orElseThrow();
		if (!message.isComment()) {
			switch (client.getMessageFrom()) {
				case TELEGRAM -> {
					try {
						if (message.getReplyMessageId() != null) {
							Message reply = messageRepository.findById(message.getReplyMessageId()).orElseThrow();
							message.setReplyMessageMessengerId(reply.getMessengerMessageId());
						}
						Integer messageId = telegramService.sendMessage(client, message);
						message.setMessengerMessageId(messageId);
					} catch (Exception e) {
						return false;
					}
				}
				case EMAIL -> emailService.sendEmail(message, client);
			}
		}
		client.getMessages().add(message);
		clientsRepository.save(client);
		return true;
	}


	public Client markRead(@NotNull ClientUser clientUser) {
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

		List<Message> list = client.getMessages().stream()
				.filter(message -> !message.isRead())
				.peek(message -> message.setRead(true))
				.toList();
		messageRepository.saveAll(list);
		return client;
	}


	public Client updateClient(Long clientId, @NotNull Map<String, Object> c) {    // FIXME
		Client client = clientsRepository.findById(clientId).orElseThrow();
		client.setFirstname((String) c.get("firstname"));
		client.setLastname((String) c.get("lastname"));
		client.setOrganization(organizationService.getOrganizations().stream()
				.filter(it -> it.getName().equals(c.get("organization")))
				.findFirst().orElse(null));
		client.setMoreInfo((String) c.get("moreInfo"));
		clientsRepository.save(client);
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
		try {
			telegramService.deleteMessage(client, message);
			message.setDeleted(true);
			messageRepository.save(message);
			return true;
		} catch (TelegramException e) {
			return false;
		}
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
