package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.ClientUser;
import ru.ravel.ItDesk.dto.ExecuteFuture;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

	private final Map<ClientUser, ExecuteFuture> clientUserMapExecutorServices = new ConcurrentHashMap<>();
	private final Map<Client, Set<User>> typingUsers = new ConcurrentHashMap<>();

	private final Logger logger = LoggerFactory.getLogger(this.getClass());


	public List<Client> getClients() {
		List<Client> clients = clientsRepository.findAll();
		clients.forEach(client -> {
			client.getMessages().sort(Message::compareTo);
			Set<User> userSet = typingUsers.get(client);
			if (userSet != null) {
				client.setTypingUsers(userSet);
			} else {
				client.setTypingUsers(Collections.emptySet());
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


	public boolean newMessage(Long clientId, @NotNull Message message) {
		message.setDate(ZonedDateTime.now());
		message.setUser(userService.getCurrentUser());
		messageRepository.save(message);
		Client client = clientsRepository.findById(clientId).orElseThrow();
		if (!message.isComment()) {
			try {
				Long messageId = telegramService.sendMessage(client, message);
				message.setMessengerMessageId(messageId);
			} catch (Exception e) {
				return false;
			}
		}
		client.getMessages().add(message);
		clientsRepository.save(client);
		return true;
	}


	public Client markRead(Long clientId) {
		Client client = clientsRepository.findById(clientId).orElseThrow();
		messageRepository.saveAll(client.getMessages().stream()
				.filter(message -> !message.isRead())
				.peek(message -> message.setRead(true))
				.toList());
		return client;
	}


	public Client updateClient(Long clientId, @NotNull Map<String, Object> c) {
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


	public void typing(@NotNull ClientUser clientUser) {
		ExecuteFuture executeFuture = clientUserMapExecutorServices.get(clientUser);
		if (executeFuture != null) {
			clientUserMapExecutorServices.get(clientUser).getFuture().cancel(true);
		} else {
			executeFuture = new ExecuteFuture();
			clientUserMapExecutorServices.put(clientUser, executeFuture);
		}
		ClientTypingWaiter task = new ClientTypingWaiter(clientUser.getClient(), clientUser.getUser(), typingUsers, logger);
		executeFuture.setFuture(executeFuture.getExecutor().submit(task));
	}

	private record ClientTypingWaiter(
			Client client,
			User user,
			Map<Client, Set<User>> typingUsers,
			Logger logger
	) implements Runnable {
		@Override
		public void run() {
			Set<User> users = typingUsers.get(client);
			if (users == null) {
				ConcurrentSkipListSet<User> listSet = new ConcurrentSkipListSet<>();
				listSet.add(user);
				typingUsers.put(client, listSet);
			} else {
				users.add(user);
			}
			try {
				Thread.sleep(30_000);
				typingUsers.get(client).remove(user);
			} catch (InterruptedException ignored) {
			}
		}
	}

}
