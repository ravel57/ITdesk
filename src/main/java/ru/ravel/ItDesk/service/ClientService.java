package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.*;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;


@Service
@RequiredArgsConstructor
public class ClientService {

	private final ClientRepository clientsRepository;
	private final TaskRepository taskRepository;
	private final MessageRepository messageRepository;
	private final TelegramService telegramService;
	private final UserService userService;
	private final TagService tagService;
//	private final StatusRepository statusRepository;


	public Client getClient(long id) {
		return clientsRepository.getReferenceById(id);
	}


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
		String username = SecurityContextHolder.getContext().getAuthentication().getName();
		User user = userService.getUsers().stream()
				.filter(it -> it.getUsername().equals(username))
				.findFirst()
				.orElse(User.builder().id(1L).build()); // FIXME
		message.setUser(user);
		messageRepository.save(message);
		Client client = clientsRepository.findById(clientId).orElseThrow();
		if (!message.isComment()) {
			telegramService.sendMessage(client, message);
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
//		client.setOrganization((String) c.get("organization"));
		client.setMoreInfo((String) c.get("moreInfo"));
		clientsRepository.save(client);
		return client;
	}
}
