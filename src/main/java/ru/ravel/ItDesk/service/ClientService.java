package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.ClientUser;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@Service
@RequiredArgsConstructor
public class ClientService {

	private final ClientRepository clientsRepository;
	private final TaskRepository taskRepository;
	private final MessageRepository messageRepository;
	private final TelegramService telegramService;
	private final UserService userService;
	private final OrganizationService organizationService;

	private volatile Map<ClientUser, ExecutorService> clientUserMap = new ConcurrentHashMap<>();

	public List<Client> getClients() {
		List<Client> clients = clientsRepository.findAll();
		clients.forEach(it -> it.getMessages().sort(Message::compareTo));
		return clients;
	}


	public Task newTask(Long clientId, @NotNull Task task) {
		Client client = clientsRepository.findById(clientId).orElseThrow();
		client.getTasks().add(task);
		taskRepository.save(task);
		clientsRepository.save(client);
		return task;
	}


	public Task updateTask(Long clientId, Task task) {
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


	public void typing(@NotNull ClientUser clientUser) throws ExecutionException, InterruptedException {
		ExecutorService pool = clientUserMap.get(clientUser);
		if (pool != null) {
			pool.shutdownNow();
		}
		pool = Executors.newSingleThreadExecutor();
		clientUserMap.put(clientUser, pool);
		pool.submit(new ClientWaiter(clientUser.getClient(), clientUser.getUser(), clientsRepository));
	}

	private static class ClientWaiter implements Runnable {
		Client client;
		User user;
		ClientRepository clientRepository;

		public ClientWaiter(Client client, User user, ClientRepository clientRepository) {
			this.client = client;
			this.user = user;
			this.clientRepository = clientRepository;
		}

		@Override
		public void run() {
			client.getTypingUsers().add(user);
			clientRepository.save(client);
			System.out.printf("added %d\n", Thread.currentThread().getId());
			try {
				Thread.sleep(7_500);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			client.getTypingUsers().remove(user);
			clientRepository.save(client);
			System.out.printf("stopped %d\n", Thread.currentThread().getId());
		}
	}

}
