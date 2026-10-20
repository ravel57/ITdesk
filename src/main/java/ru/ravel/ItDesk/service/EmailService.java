package ru.ravel.ItDesk.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.FlagTerm;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.ClientMessage;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.EmailAccount;
import ru.ravel.ItDesk.model.MessageFrom;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.EmailAccountRepository;
import ru.ravel.ItDesk.repository.MessageRepository;

import java.io.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
public class EmailService {

	private static final int MAX_INBOUND_ATTACHMENT_BYTES = 10 * 1024 * 1024;

	private final ClientRepository clientRepository;
	private final MessageRepository messageRepository;
	private final EmailAccountRepository emailAccountRepository;
	private final MinioClient minioClient;
	private final MinioService minioService;
	private final WebSocketService webSocketService;
	private final EventPublisher eventPublisher;

	private final Map<EmailAccount, Store> imapStores = new ConcurrentHashMap<>();
	private final Map<EmailAccount, JavaMailSender> smtpSenders = new ConcurrentHashMap<>();
	private final AtomicBoolean emailCheckRunning = new AtomicBoolean(false);

	@Value("${minio.bucket-name}")
	private String bucketName;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());


	public EmailService(ClientRepository clientRepository,
						MessageRepository messageRepository, EmailAccountRepository emailAccountRepository,
						MinioClient minioClient, MinioService minioService, WebSocketService webSocketService,
						EventPublisher eventPublisher) {
		this.clientRepository = clientRepository;
		this.messageRepository = messageRepository;
		this.emailAccountRepository = emailAccountRepository;
		this.minioClient = minioClient;
		this.minioService = minioService;
		this.webSocketService = webSocketService;
		emailAccountRepository.findAll().forEach(this::addMailAccount);
		this.eventPublisher = eventPublisher;
	}


	public List<EmailAccount> getEmailsAccounts() {
		return emailAccountRepository.findAll();
	}


	public EmailAccount newEmailAccount(EmailAccount emailAccount) {
		if (emailAccount == null || emailAccount.getPassword() == null || emailAccount.getPassword().isBlank()) {
			throw new IllegalArgumentException("Email account password must not be blank");
		}
		addMailAccount(emailAccount);
		return emailAccountRepository.save(emailAccount);
	}


	public EmailAccount updateEmailAccount(EmailAccount emailAccount) {
		if (emailAccount == null || emailAccount.getId() == null) {
			throw new IllegalArgumentException("Email account id must not be null");
		}
		EmailAccount existing = emailAccountRepository.findById(emailAccount.getId()).orElseThrow();
		if (emailAccount.getPassword() == null || emailAccount.getPassword().isBlank()) {
			emailAccount.setPassword(existing.getPassword());
		}
		addMailAccount(emailAccount);
		return emailAccountRepository.save(emailAccount);
	}


	public void deleteEmailAccount(Long emailId) {
		emailAccountRepository.deleteById(emailId);
	}


	public void sendEmail(@NotNull ru.ravel.ItDesk.model.Message message, @NotNull Client client) throws MessagingException, IOException {
		EmailAccount emailAccount = client.getEmailAccountSender();
		JavaMailSender mailSender = smtpSenders.get(emailAccount);
		MimeMessage mimeMessage = mailSender.createMimeMessage();
		MimeMessageHelper emailMessage = new MimeMessageHelper(mimeMessage, true);
		File file = null;
		emailMessage.setTo(client.getEmail());
		emailMessage.setSubject(emailAccount.getSubject());
		emailMessage.setText(message.getText());
		emailMessage.setFrom(emailAccount.getEmailFrom());
		if (message.getFileUuid() != null) {
			file = minioService.getFile(message.getFileName(), message.getFileUuid());
			emailMessage.addAttachment(file.getName(), file);
			if (MediaType.IMAGE_JPEG_VALUE.equals(message.getFileType())
					|| MediaType.IMAGE_PNG_VALUE.equals(message.getFileType())) {
				SafeImageMetadata.Dimensions dimensions = SafeImageMetadata.read(file);
				message.setFileWidth(dimensions.width());
				message.setFileHeight(dimensions.height());
			}
		}
		mailSender.send(mimeMessage);
		if (file != null && !file.delete()) {
			logger.error("File not deleted {}", file.getAbsolutePath());
		}
	}

	@Transactional
	@Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
	public void checkEmails() {
		if (!emailCheckRunning.compareAndSet(false, true)) {
			logger.warn("Previous email check is still running, skip this run");
			return;
		}
		try {
			imapStores.forEach((emailAccount, store) -> {
				try {
					receiveEmails(store, emailAccount);
				} catch (Exception e) {
					logger.error(e.getMessage(), e);
				}
			});
		} finally {
			emailCheckRunning.set(false);
		}
	}


	public void addMailAccount(EmailAccount emailAccount) {
		try {
			Properties imapProps = new Properties();
			imapProps.put("mail.store.protocol", "imaps");
			imapProps.put("mail.imaps.host", emailAccount.getImapServer());
			imapProps.put("mail.imaps.port", emailAccount.getImapPort());
			imapProps.put("mail.imaps.ssl.enable", "true");
			Session imapSession = Session.getDefaultInstance(imapProps);
			Store store = imapSession.getStore("imaps");
			store.connect(emailAccount.getImapServer(), emailAccount.getEmailFrom(), emailAccount.getPassword());
			imapStores.put(emailAccount, store);
			JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
			mailSender.setHost(emailAccount.getSmtpServer());
			mailSender.setPort(emailAccount.getSmtpPort());
			mailSender.setUsername(emailAccount.getEmailFrom());
			mailSender.setPassword(emailAccount.getPassword());
			Properties smtpProps = mailSender.getJavaMailProperties();
			smtpProps.put("mail.transport.protocol", "smtp");
			smtpProps.put("mail.smtp.auth", "true");
			smtpProps.put("mail.smtp.starttls.enable", "true");
			smtpProps.put("mail.smtp.ssl.enable", "true");
			smtpSenders.put(emailAccount, mailSender);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
	}


	public void receiveEmails(Store store, EmailAccount emailAccount) {
		try {
			Folder emailFolder = store.getFolder("INBOX");
			emailFolder.open(Folder.READ_WRITE);
			Message[] messages = emailFolder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
			List<Message> emails = Arrays.stream(messages).toList();
			for (Message emailMessage : emails) {
				String emailFrom = ((InternetAddress) emailMessage.getFrom()[0]).getAddress();
				Client client = clientRepository.findFirstByEmail(emailFrom).orElse(null);
				ru.ravel.ItDesk.model.Message message = ru.ravel.ItDesk.model.Message.builder()
						.fromSource("EMAIL")
						.text(getTextFromMessage(emailMessage))
						.date(ZonedDateTime.ofInstant(
								Instant.ofEpochMilli(emailMessage.getReceivedDate().getTime()),
								ZoneId.systemDefault()
						))
						.isComment(false)
						.isRead(false)
						.isSent(false)
						.build();
				saveAttachments(emailMessage, message);
				ru.ravel.ItDesk.model.Message savedMessage = messageRepository.saveAndFlush(message);
				boolean newClient = false;
				if (client != null) {
					if (client.getMessages() == null) {
						client.setMessages(new ArrayList<>());
					}
					if (client.getMessageFrom() == null) {
						client.setMessageFrom(MessageFrom.EMAIL);
					}
					if (client.getEmailAccountSender() == null) {
						client.setEmailAccountSender(emailAccount);
					}
					client.getMessages().add(savedMessage);
				} else {
					newClient = true;
					client = Client.builder()
							.email(emailFrom)
							.firstname(emailFrom)
							.messageFrom(MessageFrom.EMAIL)
							.messages(new ArrayList<>(List.of(savedMessage)))
							.emailAccountSender(emailAccount)
							.build();
				}
				try {
					client = clientRepository.saveAndFlush(client);
				} catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
					if (client.getId() == null) {
						throw e;
					}
					logger.warn("Client was updated concurrently, reloading client id={}", client.getId());
					Client freshClient = clientRepository.findById(client.getId()).orElseThrow();
					if (freshClient.getMessages() == null) {
						freshClient.setMessages(new ArrayList<>());
					}
					boolean alreadyExists = freshClient.getMessages().stream()
							.anyMatch(existingMessage -> Objects.equals(existingMessage.getId(), savedMessage.getId()));
					if (!alreadyExists) {
						freshClient.getMessages().add(savedMessage);
					}
					if (freshClient.getMessageFrom() == null) {
						freshClient.setMessageFrom(MessageFrom.EMAIL);
					}
					if (freshClient.getEmailAccountSender() == null) {
						freshClient.setEmailAccountSender(emailAccount);
					}
					client = clientRepository.saveAndFlush(freshClient);
				}
				if (newClient) {
					eventPublisher.publish(TriggerType.CLIENT_CREATED, Map.of(
							"client", client,
							"message", savedMessage
					));
				}
				if (savedMessage.getFileUuid() != null) {
					eventPublisher.publish(TriggerType.ATTACHMENT_ADDED, Map.of(
							"client", client,
							"message", savedMessage
					));
				}
				eventPublisher.publish(TriggerType.MESSAGE_INCOMING, Map.of(
						"client", client,
						"message", savedMessage
				));
				webSocketService.sendNewMessages(new ClientMessage(client, savedMessage));
				emailMessage.setFlag(Flags.Flag.SEEN, true);
			}
			emailFolder.close(false);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
	}


	private String getTextFromMessage(@NotNull Message message) throws MessagingException, IOException {
		String result = "";
		if (message.isMimeType("text/plain")) {
			result = message.getContent().toString();
		} else if (message.isMimeType("multipart/*")) {
			MimeMultipart mimeMultipart = (MimeMultipart) message.getContent();
			result = getTextFromMimeMultipart(mimeMultipart);
		} else if (message.isMimeType("text/html")) {
			String html = (String) message.getContent();
			result = Jsoup.parse(html).text();
		}
		return result;
	}


	@NotNull
	private String getTextFromMimeMultipart(@NotNull MimeMultipart mimeMultipart) throws MessagingException, IOException {
		StringBuilder result = new StringBuilder();
		int count = mimeMultipart.getCount();
		for (int i = 0; i < count; i++) {
			BodyPart bodyPart = mimeMultipart.getBodyPart(i);
			if (bodyPart.isMimeType("text/plain")) {
				result.append(bodyPart.getContent());
			} else if (bodyPart.isMimeType("text/html")) {
				String html = (String) bodyPart.getContent();
				result.append(Jsoup.parse(html).text());
			}
		}
		return result.toString();
	}


	private void saveAttachments(@NotNull Message emailMessage, ru.ravel.ItDesk.model.Message message) throws Exception {
		if (!emailMessage.isMimeType("multipart/*")) {
			return;
		}
		Multipart multipart = (Multipart) emailMessage.getContent();
		for (int i = 0; i < multipart.getCount(); i++) {
			BodyPart bodyPart = multipart.getBodyPart(i);
			if (!Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())
					&& (bodyPart.getFileName() == null || bodyPart.getFileName().isEmpty())) {
				continue;
			}

			String uuid = UUID.randomUUID().toString();
			try (InputStream is = bodyPart.getInputStream()) {
				byte[] attachmentData = is.readNBytes(MAX_INBOUND_ATTACHMENT_BYTES + 1);
				if (attachmentData.length > MAX_INBOUND_ATTACHMENT_BYTES) {
					logger.warn("Skipping email attachment larger than {} bytes", MAX_INBOUND_ATTACHMENT_BYTES);
					continue;
				}

				String contentType = bodyPart.getContentType();
				Pattern pattern = Pattern.compile("^[^;\\r\\n]+");
				Matcher matcher = pattern.matcher(contentType == null ? "" : contentType);
				contentType = matcher.find() ? matcher.group().trim() : MediaType.APPLICATION_OCTET_STREAM_VALUE;

				SafeImageMetadata.Dimensions dimensions = null;
				if (MediaType.IMAGE_PNG_VALUE.equalsIgnoreCase(contentType)
						|| MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(contentType)) {
					try (InputStream imageInput = new ByteArrayInputStream(attachmentData)) {
						dimensions = SafeImageMetadata.read(imageInput);
					}
				}

				try (InputStream attachmentStream = new ByteArrayInputStream(attachmentData)) {
					minioClient.putObject(PutObjectArgs.builder()
							.bucket(bucketName)
							.object(uuid)
							.stream(attachmentStream, attachmentData.length, -1)
							.contentType(contentType)
							.build()
					);
				}
				message.setFileUuid(uuid);
				String fileName = bodyPart.getFileName();
				message.setFileName(fileName != null && fileName.length() > 255
						? fileName.substring(0, 255) : fileName);
				message.setFileType(contentType);
				if (dimensions != null) {
					message.setFileWidth(dimensions.width());
					message.setFileHeight(dimensions.height());
				}
				logger.debug("Saved attachment to MinIO: {}", bodyPart.getFileName());
				// The message schema can reference one attachment only. Keeping the first
				// valid attachment avoids creating unreachable objects for later parts.
				return;
			} catch (Exception e) {
				logger.warn("Skipping unsafe or invalid email attachment: {}", e.getMessage());
			}
		}
	}

}
