package ru.ravel.ItDesk.service;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.TelegramException;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.PhotoSize;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.GetFile;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.ClientMessage;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.MessageFrom;
import ru.ravel.ItDesk.model.TgBot;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.TelegramRepository;
import ru.ravel.ItDesk.telegrammessagebuilder.MessageBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;


@Service
public class TelegramService {

	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final TelegramRepository telegramRepository;
	private final ClientRepository clientRepository;
	private final MessageRepository messageRepository;
	private final MinioClient minioClient;
	private final MinioService minioService;
	private final WebSocketService webSocketService;

	@Value("${minio.bucket-name}")
	private String bucketName;


	TelegramService(ClientRepository clientRepository, MessageRepository messageRepository,
					TelegramRepository telegramRepository, MinioClient minioClient, MinioService minioService,
					WebSocketService webSocketService) {
		this.telegramRepository = telegramRepository;
		this.messageRepository = messageRepository;
		this.clientRepository = clientRepository;
		this.minioClient = minioClient;
		this.minioService = minioService;
		this.webSocketService = webSocketService;
		telegramRepository.findAll().stream()
				.map(TgBot::getBot)
				.forEach(bot -> bot.setUpdatesListener(new BotUpdatesListener(bot)));
	}


	public void sendMessage(@NotNull Client client, @NotNull Message message) throws TelegramException {
		try {
			Integer messageId = null;
			if (message.getReplyMessageId() != null) {
				Message reply = messageRepository.findById(message.getReplyMessageId()).orElseThrow();
				message.setReplyMessageMessengerId(reply.getMessengerMessageId());
			}
			TelegramBot bot = client.getTgBot().getBot();
			if (message.getFileUuid() != null) {
				File file = minioService.getFile(message.getFileName(), message.getFileUuid());
				messageId = new MessageBuilder(bot)
						.document()
						.telegramId(client.getTelegramId())
						.file(file)
						.execute();
				if (message.getFileType().equals(MediaType.IMAGE_JPEG_VALUE) || message.getFileType().equals("image/webp")) {
					BufferedImage bufferedImage = ImageIO.read(file);
					message.setFileWidth(bufferedImage.getWidth());
					message.setFileHeight(bufferedImage.getHeight());
				}
				if (file != null && !file.delete()) {
					logger.error("File not deleted {}", file.getAbsolutePath());
				}
			}
			if (message.getText().isEmpty()) {
				message.setMessengerMessageId(messageId);
			} else {
				messageId = new MessageBuilder(bot)
						.send()
						.telegramId(client.getTelegramId())
						.text(message.getText())
						.replyMessage(message.getReplyMessageMessengerId())
						.execute();
				message.setMessengerMessageId(messageId);
			}
		} catch (Exception e) {
			logger.error(e.getMessage());
			throw new TelegramException(new RuntimeException(e.getMessage()));
		}
	}


	public List<TgBot> getTelegramBots() {
		return telegramRepository.findAll();
	}


	public TgBot newTelegramBot(@NotNull TgBot tgBot) {
		tgBot.getBot().setUpdatesListener(new BotUpdatesListener(tgBot.getBot()));
		return telegramRepository.save(tgBot);
	}


	public TgBot updateTelegramBot(@NotNull TgBot tgBot) {
		tgBot.getBot().setUpdatesListener(new BotUpdatesListener(tgBot.getBot()));
		return telegramRepository.save(tgBot);
	}


	public void deleteTelegramBot(Long tgBotId) {
		telegramRepository.deleteById(tgBotId);
	}


	public void deleteMessage(@NotNull Client client, Message message) throws TelegramException {
		TelegramBot bot = client.getTgBot().getBot();
		try {
			new MessageBuilder(bot)
					.delete()
					.telegramId(client.getTelegramId())
					.messageId(message.getMessengerMessageId())
					.execute();
		} catch (Exception e) {
			logger.error(e.getMessage());
			throw new TelegramException(new RuntimeException("Message not deleted"));
		}
	}

	@RequiredArgsConstructor
	private class BotUpdatesListener implements UpdatesListener {

		private final TelegramBot bot;

		@Override
		public int process(List<Update> updates) {
			try {
				updates.forEach(update -> {
					Client client = clientRepository.findByTelegramId(update.message().from().id());    // FIXME Прилетает не из того бота сообщение
					ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochSecond(update.message().date()), ZoneId.systemDefault());
					Message message = Message.builder()
							.text(update.message().text())
							.date(zonedDateTime)
							.isSent(false)
							.isComment(false)
							.isRead(false)
							.messengerMessageId(update.message().messageId())
							.build();
					if (update.message().replyToMessage() != null) {
						Integer reply = update.message().replyToMessage().messageId();
						message.setReplyMessageMessengerId(reply);
						Message messengerMessage = messageRepository.findByMessengerMessageId(reply).orElseThrow();
						message.setReplyMessageId(messengerMessage.getId());
					}
					saveAttachments(update, message);
					messageRepository.save(message);
					if (client != null) {
						client.getMessages().add(message);
					} else {
						TgBot tgBot = telegramRepository.findByToken(bot.getToken());    // FIXME
						client = Client.builder()
								.firstname(update.message().from().firstName())
								.lastname(update.message().from().lastName())
								.telegramId(update.message().from().id())
								.messages(List.of(message))
								.messageFrom(MessageFrom.TELEGRAM)
								.tgBot(tgBot)
								.build();
					}
					clientRepository.save(client);
					webSocketService.sendNewMessages(new ClientMessage(client, message));
				});
			} catch (Exception e) {
				logger.error(e.getMessage());
			}
			return UpdatesListener.CONFIRMED_UPDATES_ALL;
		}

		private void saveAttachments(@NotNull Update update, Message message) {
			GetFile request;
			String type;
			if (update.message().photo() != null) {
				request = new GetFile(Arrays.stream(update.message().photo())
						.max(Comparator.comparing(PhotoSize::height))
						.orElseThrow()
						.fileId());
				type = MediaType.IMAGE_JPEG_VALUE;
			} else if (update.message().video() != null) {
				request = new GetFile(update.message().video().fileId());
				type = update.message().video().mimeType();
			} else if (update.message().document() != null) {
				request = new GetFile(update.message().document().fileId());
				type = update.message().document().mimeType();
				message.setFileName(update.message().document().fileName());
			} else if (update.message().voice() != null) {
				request = new GetFile(update.message().voice().fileId());
				type = update.message().voice().mimeType();
			} else if (update.message().sticker() != null) {
				request = new GetFile(update.message().sticker().fileId());
				if (update.message().sticker().isVideo()) {
					type = "video/webm";
				} else if (update.message().sticker().isAnimated()) {
					type = null;    // FIXME
				} else {
					type = "image/webp";
				}
			} else {
				return;
			}
			try {
				String path = this.bot.getFullFilePath(bot.execute(request).file());
				HttpURLConnection connection = (HttpURLConnection) new URL(path).openConnection();    //FIXME
				InputStream inputStream = connection.getInputStream();
				String uuid = UUID.randomUUID().toString();
				minioClient.putObject(PutObjectArgs.builder()
						.bucket(bucketName)
						.object(uuid)
						.stream(inputStream, connection.getContentLengthLong(), -1)
						.contentType(type)
						.build());
				inputStream.close();
				if (update.message().caption() != null) {
					message.setText(update.message().caption());
				}
				message.setFileType(type);
				message.setFileUuid(uuid);
				if (type != null && (type.equals(MediaType.IMAGE_JPEG_VALUE) || type.equals("image/webp"))) {
					File file = minioService.getFile("none", message.getFileUuid());
					BufferedImage bufferedImage = ImageIO.read(file);
					message.setFileWidth(bufferedImage.getWidth());
					message.setFileHeight(bufferedImage.getHeight());
					boolean delete = file.delete();
				}
			} catch (Exception e) {
				logger.error(e.getMessage());
			}
		}
	}

}
