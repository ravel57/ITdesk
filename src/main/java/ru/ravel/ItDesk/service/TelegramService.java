package ru.ravel.ItDesk.service;

import com.pengrad.telegrambot.ExceptionHandler;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.TgBot;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.TelegramRepository;
import ru.ravel.ItDesk.telegrammessagebuilder.MessageBuilder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;


@Service
public class TelegramService {

	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final TelegramRepository telegramRepository;
	private final ClientRepository clientRepository;
	private final MessageRepository messageRepository;


	TelegramService(ClientRepository clientRepository, MessageRepository messageRepository, TelegramRepository telegramRepository) {
		this.telegramRepository = telegramRepository;
		this.messageRepository = messageRepository;
		this.clientRepository = clientRepository;
		telegramRepository.findAll().stream()
				.map(TgBot::getBot)
				.forEach(bot -> bot.setUpdatesListener(new BotUpdatesListener(bot), exceptionHandler)
				);
	}


	// FIXME send to front
	ExceptionHandler exceptionHandler = e -> {
		if (e.response() != null) {
			logger.error(String.valueOf(e.response().errorCode()), e.response().description());
		} else {
			logger.error(e.getMessage());
		}
	};


	public void sendMessage(@NotNull Client client, @NotNull Message message) {
		TelegramBot bot = client.getTgBot().getBot();
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


	public List<TgBot> getTelegramBots() {
		return telegramRepository.findAll();
	}


	public TgBot newTelegramBot(@NotNull TgBot tgBot) {
		tgBot.getBot().setUpdatesListener(new BotUpdatesListener(tgBot.getBot()), exceptionHandler);
		return telegramRepository.save(tgBot);
	}

	public TgBot updateTelegramBot(TgBot telegramBot) {
		return telegramRepository.save(telegramBot);
	}

	public void deleteTelegramBot(Long tgBotId) {
		telegramRepository.deleteById(tgBotId);
	}


	class BotUpdatesListener implements UpdatesListener {
		private final TelegramBot bot;

		public BotUpdatesListener(TelegramBot bot) {
			this.bot = bot;
		}

		@Override
		public int process(List<Update> updates) {
			try {
				updates.forEach(it -> {
					Client client = clientRepository.findByTelegramId(it.message().from().id());
					ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochSecond(it.message().date()), ZoneId.systemDefault());
					Message message = Message.builder()
							.text(it.message().text())
							.date(zonedDateTime)
							.isSent(false)
							.isComment(false)
							.isRead(false)
							.build();
					messageRepository.save(message);
					if (client == null) {
						client = Client.builder()
								.firstname(it.message().from().firstName())
								.lastname(it.message().from().lastName())
								.telegramId(it.message().from().id())
								.messages(List.of(message))
								.tgBot(telegramRepository.findByToken(bot.getToken()))    //FIXME
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
		}
	}

}
