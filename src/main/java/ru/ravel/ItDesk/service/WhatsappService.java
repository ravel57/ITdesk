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
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.WhatsappAccountRepository;
import ru.ravel.ItDesk.whatsappdto.*;

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

	private static final int MAX_MEDIA_BYTES = 10 * 1024 * 1024;
	private static final int MAX_BASE64_CHARS = ((MAX_MEDIA_BYTES + 2) / 3) * 4 + 4;

	private final WhatsappFeign whatsappFeign;
	private final WhatsappAccountRepository whatsappAccountRepository;
	private final ClientRepository clientRepository;
	private final MinioClient minioClient;
	private final MessageRepository messageRepository;
	private final MinioService minioService;

	private final List<WhatsappAccount> whatsappAccounts = Collections.synchronizedList(new ArrayList<>());
	private final WebSocketService webSocketService;
	private final EventPublisher eventPublisher;

	@Value("${minio.bucket-name}")
	private String bucketName;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());


	public WhatsappService(WhatsappFeign whatsappFeign, WhatsappAccountRepository whatsappAccountRepository,
						   ClientRepository clientRepository, MinioClient minioClient,
						   MessageRepository messageRepository, MinioService minioService,
						   WebSocketService webSocketService, EventPublisher eventPublisher) {
		this.whatsappFeign = whatsappFeign;
		this.whatsappAccountRepository = whatsappAccountRepository;
		this.clientRepository = clientRepository;
		this.minioClient = minioClient;
		this.messageRepository = messageRepository;
		this.minioService = minioService;
		this.webSocketService = webSocketService;
		this.whatsappAccounts.addAll(getWhatsappAccounts());
		this.eventPublisher = eventPublisher;
	}


	public List<WhatsappAccount> getWhatsappAccounts() {
		return whatsappAccountRepository.findAll();
	}


	public WhatsappAccount newWhatsappAccount(WhatsappAccount whatsappAccount) {
		if (whatsappAccount == null || whatsappAccount.getApiKey() == null || whatsappAccount.getApiKey().isBlank()) {
			throw new IllegalArgumentException("WhatsApp API key must not be blank");
		}
		WhatsappAccount saved = whatsappAccountRepository.save(whatsappAccount);
		whatsappAccounts.add(saved);
		return saved;
	}


	public WhatsappAccount updateWhatsappAccount(WhatsappAccount whatsappAccount) {
		if (whatsappAccount == null || whatsappAccount.getId() == null) {
			throw new IllegalArgumentException("WhatsApp account id must not be null");
		}
		WhatsappAccount existing = whatsappAccountRepository.findById(whatsappAccount.getId()).orElseThrow();
		if (whatsappAccount.getApiKey() == null || whatsappAccount.getApiKey().isBlank()) {
			whatsappAccount.setApiKey(existing.getApiKey());
		}
		WhatsappAccount saved = whatsappAccountRepository.save(whatsappAccount);
		synchronized (whatsappAccounts) {
			whatsappAccounts.removeIf(item -> Objects.equals(item.getId(), saved.getId()));
			whatsappAccounts.add(saved);
		}
		return saved;
	}


	public void deleteWhatsappAccount(Long accountId) {
		whatsappAccountRepository.deleteById(accountId);
		synchronized (whatsappAccounts) {
			whatsappAccounts.removeIf(item -> Objects.equals(item.getId(), accountId));
		}
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
				eventPublisher.publish(TriggerType.CLIENT_CREATED, Map.of("client", client, "message", message));
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
			try {
				messageBody.media.data = encodeFileToBase64(file);
				if (MediaType.IMAGE_JPEG_VALUE.equals(message.getFileType())
						|| MediaType.IMAGE_PNG_VALUE.equals(message.getFileType())) {
					SafeImageMetadata.Dimensions dimensions = SafeImageMetadata.read(file);
					message.setFileWidth(dimensions.width());
					message.setFileHeight(dimensions.height());
				}
			} finally {
				if (file.exists() && !file.delete()) {
					logger.error("File not deleted {}", file.getAbsolutePath());
				}
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
		try {
			if (media.data == null || media.data.isBlank() || media.data.length() > MAX_BASE64_CHARS) {
				throw new IOException("WhatsApp media is empty or too large");
			}
			byte[] decodedBytes = Base64.getDecoder().decode(media.data);
			if (decodedBytes.length > MAX_MEDIA_BYTES) {
				throw new IOException("WhatsApp media exceeds upload limit");
			}

			SafeImageMetadata.Dimensions dimensions = null;
			if (MediaType.IMAGE_JPEG_VALUE.equals(media.mimetype)
					|| MediaType.IMAGE_PNG_VALUE.equals(media.mimetype)) {
				try (InputStream imageInput = new ByteArrayInputStream(decodedBytes)) {
					dimensions = SafeImageMetadata.read(imageInput);
				}
			}

			try (InputStream inputStream = new ByteArrayInputStream(decodedBytes)) {
				minioClient.putObject(
						PutObjectArgs.builder()
								.bucket(bucketName)
								.object(uuid)
								.contentType(media.mimetype == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : media.mimetype)
								.stream(inputStream, decodedBytes.length, -1)
								.build()
				);
			}
			message.setFileType(media.mimetype);
			message.setFileUuid(uuid);
			String fileName = media.filename;
			message.setFileName(fileName != null && fileName.length() > 255
					? fileName.substring(0, 255) : fileName);
			if (dimensions != null) {
				message.setFileWidth(dimensions.width());
				message.setFileHeight(dimensions.height());
			} else if ("image/webp".equals(media.mimetype)) {
				message.setFileWidth(512); // Provider does not expose WebP dimensions here.
				message.setFileHeight(512);
			}
		} catch (Exception e) {
			logger.warn("Skipping unsafe or invalid WhatsApp media: {}", e.getMessage());
		}
	}


	public static String encodeFileToBase64(File file) throws IOException {
		long size = file.length();
		if (size < 0 || size > MAX_MEDIA_BYTES) {
			throw new IOException("WhatsApp media exceeds upload limit");
		}
		byte[] fileBytes = new byte[(int) size];
		try (FileInputStream fileInputStream = new FileInputStream(file)) {
			int offset = 0;
			while (offset < fileBytes.length) {
				int read = fileInputStream.read(fileBytes, offset, fileBytes.length - offset);
				if (read < 0) {
					throw new EOFException("Unexpected end of media file");
				}
				offset += read;
			}
		}
		return Base64.getEncoder().encodeToString(fileBytes);
	}

}