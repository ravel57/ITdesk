package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.models.Client;
import ru.ravel.ItDesk.models.Message;
import ru.ravel.ItDesk.models.Task;
import ru.ravel.ItDesk.reposetory.ClientRepository;
import ru.ravel.ItDesk.reposetory.MessageRepository;
import ru.ravel.ItDesk.reposetory.TaskRepository;

import java.util.List;


@Service
@RequiredArgsConstructor
public class ClientService {

	private final ClientRepository clientsRepository;
	private final TaskRepository taskRepository;
	private final MessageRepository messageRepository;
//	String login = SecurityContextHolder.getContext().getAuthentication().getName();

	public Client getClient(long id) {
		return clientsRepository.getReferenceById(id);
	}


	public List<Client> getClients() {
		return clientsRepository.findAll();
	}


	public Task newTask(Long clientId, Task task) {
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
		messageRepository.save(message);
		Client client = clientsRepository.findById(clientId).orElseThrow();
		client.getMessages().add(message);
		clientsRepository.save(client);
		return true;
	}


//    public Client getClientByTelegramUser(User user) {
//        Client client;
//        long telegramId = user.getId();
//        if (idsLocalCash.contains(telegramId)) {
//            client = clientsDAO.getClientByTelegramId(telegramId);
//        } else {
//            if (this.checkRegisteredByTelegramId(telegramId)) {
//                client = clientsDAO.getClientByTelegramId(telegramId);
//            } else {
//                client = Client.builder()
//                        .firstName(user.getFirstName())
//                        .lastName(user.getLastName())
//                        .userName(user.getUserName())
//                        .telegramId(telegramId)
//                        .build();
//                clientsDAO.addClient(client);
//                client.setId(clientsDAO.getClientByTelegramId(telegramId).getId());
//            }
//            idsLocalCash.add(telegramId);
//            // todo delete asynchronously when idle time is exceeded
//        }
//        return client;
//    }


//	//    @Override
//	public String getTelegramIdByClientId(long clientId) {
//		return clientsDAO.getTelegramIdByClientId(clientId);
//	}
//
//	//    @Override
//	public boolean checkRegisteredByTelegramId(long telegramId) {
//		return (clientsDAO.getClientByTelegramId(telegramId).getId() != 0);
//	}


//    public void sendClientToFront(Client client) {
//        this.simpMessaging.convertAndSend("/topic/activity", client);
//    }

}
