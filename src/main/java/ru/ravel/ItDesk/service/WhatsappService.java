package ru.ravel.ItDesk.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.feign.WhatsappFeign;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.MessageFrom;
import ru.ravel.ItDesk.model.WhatsappAccount;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.WhatsappAccountRepository;
import ru.ravel.ItDesk.whatsapp.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;


@Service
public class WhatsappService {

	private final WhatsappFeign whatsappFeign;
	private final WhatsappAccountRepository whatsappAccountRepository;
	private final ClientRepository clientRepository;
	private final MinioClient minioClient;
	private final MessageRepository messageRepository;
	private final MinioService minioService;

	private final List<WhatsappAccount> whatsappAccounts = Collections.synchronizedList(new ArrayList<>());

	@Value("${minio.bucket-name}")
	private String bucketName;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());


	public WhatsappService(WhatsappFeign whatsappFeign, WhatsappAccountRepository whatsappAccountRepository,
						   ClientRepository clientRepository, MinioClient minioClient,
						   MessageRepository messageRepository, MinioService minioService) {
		this.whatsappFeign = whatsappFeign;
		this.whatsappAccountRepository = whatsappAccountRepository;
		this.clientRepository = clientRepository;
		this.minioClient = minioClient;
		this.messageRepository = messageRepository;
		this.minioService = minioService;
		whatsappAccounts.addAll(getWhatsappAccounts());
	}


	public List<WhatsappAccount> getWhatsappAccounts() {
		return whatsappAccountRepository.findAll();
	}


	public WhatsappAccount newWhatsappAccount(WhatsappAccount whatsappAccount) {
		whatsappAccountRepository.save(whatsappAccount);
		whatsappAccounts.add(whatsappAccount);
		return whatsappAccount;
	}


	public WhatsappAccount updateWhatsappAccount(WhatsappAccount whatsappAccount) {
		return whatsappAccountRepository.save(whatsappAccount);
	}


	public void deleteWhatsappAccount(Long accountId) {
		whatsappAccountRepository.deleteById(accountId);
	}


	@Async
	@Scheduled(fixedRate = 5, timeUnit = TimeUnit.SECONDS)
	public void checkEmails() {
		whatsappAccounts.forEach(whatsappAccount -> {
			try {
				getMessages(whatsappAccount);
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
			}
		});
	}


	public void getMessages(@NotNull WhatsappAccount whatsappAccount) {
		UpdateResponse update = whatsappFeign.getUpdate(whatsappAccount.getApiKey(), UpdateBody.builder()
				.whatsappID(whatsappAccount.getWhatsappId())
				.action(Action.message)
				.pageCnt(100)
				.page(1)
				.date(whatsappAccount.getLastUpdate())
				.build());
		List<EventData> updates = new ArrayList<>(update.data.stream().map(responseData -> responseData.event_data).toList());
		for (int i = 1; i < update.pages; i++) {
			update = whatsappFeign.getUpdate(whatsappAccount.getApiKey(), UpdateBody.builder()
					.whatsappID(whatsappAccount.getWhatsappId())
					.action(Action.message)
					.pageCnt(100)
					.page(i)
					.date(whatsappAccount.getLastUpdate())
					.build());
			updates.addAll(update.data.stream().map(responseData -> responseData.event_data).toList());
		}
		whatsappAccount.setLastUpdate(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
				.format(ZonedDateTime.now().withZoneSameInstant(ZoneOffset.UTC)));
		whatsappAccountRepository.save(whatsappAccount);
		updates.forEach(eventData -> {
			Message message = Message.builder()
					.date(ZonedDateTime.ofInstant(Instant.ofEpochSecond(eventData.message.timestamp), ZoneId.systemDefault()))
					.text(eventData.message.body)
					.isSent(false)
					.isRead(false)
					.isComment(false)
					.messengerMessageId(null)
					.build();
			if (eventData.message.hasMedia) {
				try {
					MediaRequestBody mediaRequestBody = new MediaRequestBody(whatsappAccount.getWhatsappId(), eventData.message.mediaKey);
					Media media = whatsappFeign.getMedia(whatsappAccount.getApiKey(), mediaRequestBody).media;
					String uuid = UUID.randomUUID().toString();
					decodeBase64ToMinio(media.data, uuid, media.mimetype, message);
				} catch (Exception e) {
					logger.error(e.getMessage(), e);
				}
			}
			messageRepository.save(message);
			Client client = clientRepository.findByWhatsappRecipient(eventData.message.from);
			if (client == null) {
				client = Client.builder()
						.firstname(eventData.message.from_name)
						.whatsappRecipient(eventData.message.from)
						.messages(List.of(message))
						.messageFrom(MessageFrom.WHATSAPP)
						.whatsappAccount(whatsappAccount)
						.build();
			} else {
				client.getMessages().add(message);
			}
			try {
				clientRepository.save(client);
			} catch (Exception e) {
				clientRepository.save(client);
			}
		});
	}


	public void sendMessage(@NotNull Message message, @NotNull Client client) {
		WhatsappAccount whatsappAccount = client.getWhatsappAccount();
		WaRequestMessage build = WaRequestMessage.builder()
				.async(false)
				.recipient(new Recipient(client.getWhatsappRecipient()))
				.message(new MessageBody(message.getText()))
				.whatsappID(whatsappAccount.getWhatsappId())
				.build();
		whatsappFeign.sendMessage(whatsappAccount.getApiKey(), build);
	}


	private void decodeBase64ToMinio(String base64String, String uuid, String type, Message message) {
		byte[] decodedBytes = Base64.getDecoder().decode(base64String);
		try (InputStream inputStream = new ByteArrayInputStream(decodedBytes)) {
			minioClient.putObject(
					PutObjectArgs.builder()
							.bucket(bucketName)
							.object(uuid)
							.contentType(type)
							.stream(inputStream, decodedBytes.length, -1)
							.build()
			);
			message.setFileType(type);
			message.setFileUuid(uuid);
			if (type != null && (type.equals(MediaType.IMAGE_JPEG_VALUE) /*||type.equals("image/webp")*/)) {
				File file = minioService.getFile("none", message.getFileUuid());
				BufferedImage bufferedImage = ImageIO.read(file);
				message.setFileWidth(bufferedImage.getWidth());
				message.setFileHeight(bufferedImage.getHeight());
				boolean delete = file.delete();
			} else if (type != null && type.equals("image/webp")) {
				message.setFileWidth(512); // FIXME
				message.setFileHeight(512); //FIXME
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
	}

}