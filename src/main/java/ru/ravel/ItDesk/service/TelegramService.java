package ru.ravel.ItDesk.service;

import com.pengrad.telegrambot.ExceptionHandler;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.reposetory.ClientRepository;
import ru.ravel.ItDesk.reposetory.MessageRepository;
import ru.ravel.ItDesk.telegrammessagebuilder.MessageBuilder;

import java.time.*;
import java.util.List;


@Service
public class TelegramService {

	private final TelegramBot bot;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());


	TelegramService(ClientRepository clientRepository, MessageRepository messageRepository) {
		bot = new TelegramBot(System.getenv("bot_token"));
		UpdatesListener listener = updates -> {
			try {
				updates.forEach(it -> {
					Client client = clientRepository.findByTelegramId(it.message().from().id());
					ZonedDateTime date = ZonedDateTime.ofInstant(Instant.ofEpochSecond(it.message().date()), ZoneId.systemDefault());
					Message message = Message.builder()
							.text(it.message().text())
							.date(date)
							.isSent(false)
							.isComment(false)
							.isRead(false)
							.build();
					messageRepository.save(message);
					if (client == null) {
						client = Client.builder()
								.firstName(it.message().from().firstName())
								.lastName(it.message().from().lastName())
								.telegramId(it.message().from().id())
								.messages(List.of(message))
								.build();
					} else {
						client.getMessages().add(message);
					}
					clientRepository.save(client);
				});
			} catch (Exception e) {
				logger.error(e.getMessage());
			}
			return UpdatesListener.CONFIRMED_UPDATES_ALL;
		};
		ExceptionHandler exceptionHandler = e -> {
			if (e.response() != null) {
				logger.error(String.valueOf(e.response().errorCode()), e.response().description());
			} else {
				logger.error(e.getMessage());
			}
		};
		bot.setUpdatesListener(listener, exceptionHandler);
	}


	public void sendMessage(@NotNull Client client, @NotNull Message message) {
		try {
			new MessageBuilder(bot)
					.send()
					.telegramId(client.getTelegramId())
					.text(message.getText())
					.execute();
		} catch (NoSuchFieldException e) {
			logger.error(e.getMessage());
		}
	}
}
