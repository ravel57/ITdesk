package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.reposetory.ClientRepository;
import ru.ravel.ItDesk.reposetory.MessageRepository;
import ru.ravel.ItDesk.reposetory.TagRepository;
import ru.ravel.ItDesk.reposetory.TaskRepository;

import java.time.ZonedDateTime;
import java.util.List;


@Service
@RequiredArgsConstructor
public class ClientService {

	private final ClientRepository clientsRepository;
	private final TaskRepository taskRepository;
	private final MessageRepository messageRepository;
	private final TagRepository tagRepository;
	private final TelegramService telegramService;
//	String login = SecurityContextHolder.getContext().getAuthentication().getName();

	public Client getClient(long id) {
		return clientsRepository.getReferenceById(id);
	}


	public List<Client> getClients() {
		return clientsRepository.findAll();
	}


	public Task newTask(Long clientId, Task task) {
		tagRepository.saveAll(task.getTags());
		taskRepository.save(task);
		Client client = clientsRepository.findById(clientId).orElseThrow();
		client.getTasks().add(task);
		clientsRepository.save(client);
		return task;
	}


	public Task updateTask(Long clientId, Task task) {
		taskRepository.save(task);
		return task;
	}

	public boolean newMessage(Long clientId, Message message) {
		message.setDate(ZonedDateTime.now());
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
		client.getMessages().forEach(message -> message.setRead(true));
		clientsRepository.save(client);
		return client;
	}
}
