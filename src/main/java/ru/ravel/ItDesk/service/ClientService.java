package ru.ravel.ItDesk.service;

import com.pengrad.telegrambot.TelegramException;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.*;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.SlaRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.Duration;
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
	private final EmailService emailService;
	private final SlaRepository slaRepository;

	private final Map<ClientUserText, ExecuteFuture> clientUserMapTypingExecutorServices = new ConcurrentHashMap<>();
	private final Map<ClientUser, ExecuteFuture> clientUserMapWatchingExecutorServices = new ConcurrentHashMap<>();
	private final Map<Client, Set<User>> typingUsers = new ConcurrentHashMap<>();
	private final Map<Client, Set<User>> watchingUsers = new ConcurrentHashMap<>();

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private final Integer pageLimit = 25;


	public List<Client> getClients() {
		List<Client> clients = clientsRepository.findAll();
		clients.forEach(client -> {
			client.getTasks().forEach(task -> task.getMessages().sort(Message::compareTo));
			client.setTypingUsers(Objects.requireNonNullElse(typingUsers.get(client), Collections.emptySet()));
			client.setWatchingUsers(Objects.requireNonNullElse(watchingUsers.get(client), Collections.emptySet()));
			client.getTasks().forEach(task -> client.getMessages().stream()
					.filter(message -> message.getId().equals(task.getLinkedMessageId()))
					.forEach(message -> message.setLinkedTaskId(task.getLinkedMessageId())));
			switch (client.getMessageFrom()) {
				case TELEGRAM -> {
					TgBot tgBot = Objects.requireNonNullElse(client.getTgBot(), new TgBot());
					client.setSourceChannel(tgBot.getName());
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
		taskRepository.save(task);
		client.getTasks().add(task);
		clientsRepository.save(client);
		return task;
	}


	public Task updateTask(Long clientId, @NotNull Task task) {
		Client client = clientsRepository.findById(clientId).orElseThrow();
		setSla(client, task);
		return taskRepository.save(task);
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


	public boolean sendMessage(Long clientId, @NotNull Message message) {
		message.setDate(ZonedDateTime.now());
		message.setUser(userService.getCurrentUser());
		messageRepository.save(message);
		Client client = clientsRepository.findById(clientId).orElseThrow();
		if (!message.isComment()) {
			try {
				switch (client.getMessageFrom()) {
					case TELEGRAM -> telegramService.sendMessage(client, message);
					case EMAIL -> emailService.sendEmail(message, client);
				}
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
				return false;
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
		switch (client.getMessageFrom()) {
			case TELEGRAM -> {
				try {
					telegramService.deleteMessage(client, message);
				} catch (TelegramException e) {
					return false;
				}
			}
			case EMAIL -> {
			}
		}
		message.setDeleted(true);
		messageRepository.save(message);
		return true;
	}


	public Boolean deleteClient(Long clientId) {
		clientsRepository.deleteById(clientId);
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
		return true;
	}

	public MessagesList getPageOfMessages(Long clientId, Integer page) {
		Client client = clientsRepository.findById(clientId).orElseThrow();
		int elementsInCurrentPage = Math.max(0, client.getMessages().size() - pageLimit * page);
		List<Message> list = client.getMessages().stream()
				.sorted()
				.skip(elementsInCurrentPage)
				.limit(pageLimit)
				.toList();
		return new MessagesList(list, elementsInCurrentPage == 0);
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