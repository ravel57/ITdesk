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
import ru.ravel.ItDesk.dto.ClientMessage;
import ru.ravel.ItDesk.feign.WhatsappFeign;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.MessageFrom;
import ru.ravel.ItDesk.model.WhatsappAccount;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.WhatsappAccountRepository;
import ru.ravel.ItDesk.whatsappdto.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
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
	private final WebSocketService webSocketService;

	@Value("${minio.bucket-name}")
	private String bucketName;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());


	public WhatsappService(WhatsappFeign whatsappFeign, WhatsappAccountRepository whatsappAccountRepository,
						   ClientRepository clientRepository, MinioClient minioClient,
						   MessageRepository messageRepository, MinioService minioService,
						   WebSocketService webSocketService) {
		this.whatsappFeign = whatsappFeign;
		this.whatsappAccountRepository = whatsappAccountRepository;
		this.clientRepository = clientRepository;
		this.minioClient = minioClient;
		this.messageRepository = messageRepository;
		this.minioService = minioService;
		this.webSocketService = webSocketService;
		this.whatsappAccounts.addAll(getWhatsappAccounts());
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
	@Scheduled(fixedRate = 10, timeUnit = TimeUnit.SECONDS)
	public void checkMessages() {
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
					decodeBase64ToMinio(media, uuid, message);
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
			webSocketService.sendNewMessages(new ClientMessage(client, message));
		});
	}


	public void sendMessage(@NotNull Message message, @NotNull Client client) throws IOException {
		MessageBody messageBody;
		if (message.getFileType()!=null && message.getFileType().startsWith("image")) {
			messageBody = MessageBody.builder()
					.type(Type.image)
					.body(message.getText())
					.media(Media.builder()
							.mimetype(message.getFileType())
							.filename(message.getFileName())
							.build())
					.build();
		} else if (message.getFileUuid() != null) {
			messageBody = MessageBody.builder()
					.type(Type.doc)
					.body(message.getText())
					.media(Media.builder()
							.mimetype(message.getFileType())
							.filename(message.getFileName())
							.build())
					.build();
		} else {
			messageBody = MessageBody.builder().body(message.getText()).build();
		}
		if (message.getFileUuid() != null) {
			File file = minioService.getFile(message.getFileName(), message.getFileUuid());
			messageBody.media.data = encodeFileToBase64(file);
			if (message.getFileType().equals(MediaType.IMAGE_JPEG_VALUE) || message.getFileType().equals(MediaType.IMAGE_PNG_VALUE)) {
				BufferedImage bufferedImage = ImageIO.read(file);
				message.setFileWidth(bufferedImage.getWidth());
				message.setFileHeight(bufferedImage.getHeight());
			}
			if (!file.delete()) {
				logger.error("File not deleted {}", file.getAbsolutePath());
			}
		}
		WhatsappAccount whatsappAccount = client.getWhatsappAccount();
		WaRequestMessage requestMessage = WaRequestMessage.builder()
				.async(false)
				.recipient(new Recipient(client.getWhatsappRecipient()))
				.message(messageBody)
				.whatsappID(whatsappAccount.getWhatsappId())
				.build();
		whatsappFeign.sendMessage(whatsappAccount.getApiKey(), requestMessage);
	}


	private void decodeBase64ToMinio(@NotNull Media media, String uuid, Message message) {
		byte[] decodedBytes = Base64.getDecoder().decode(media.data);
		try (InputStream inputStream = new ByteArrayInputStream(decodedBytes)) {
			minioClient.putObject(
					PutObjectArgs.builder()
							.bucket(bucketName)
							.object(uuid)
							.contentType(media.mimetype)
							.stream(inputStream, decodedBytes.length, -1)
							.build()
			);
			message.setFileType(media.mimetype);
			message.setFileUuid(uuid);
			message.setFileName(media.filename);
			if (media.mimetype != null && media.mimetype.equals(MediaType.IMAGE_JPEG_VALUE)) {
				File file = minioService.getFile("none", message.getFileUuid());
				BufferedImage bufferedImage = ImageIO.read(file);
				message.setFileWidth(bufferedImage.getWidth());
				message.setFileHeight(bufferedImage.getHeight());
				boolean delete = file.delete();
			} else if (media.mimetype != null && media.mimetype.equals("image/webp")) {
				message.setFileWidth(512); // FIXME
				message.setFileHeight(512); //FIXME
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
	}


	public static String encodeFileToBase64(File file) throws IOException {
		byte[] fileBytes;
		try (FileInputStream fileInputStream = new FileInputStream(file)) {
			fileBytes = new byte[(int) file.length()];
			fileInputStream.read(fileBytes);
		}
		String base64String = Base64.getEncoder().encodeToString(fileBytes);
		return base64String;
	}

}