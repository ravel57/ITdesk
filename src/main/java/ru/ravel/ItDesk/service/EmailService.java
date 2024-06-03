package ru.ravel.ItDesk.service;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.FlagTerm;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.EmailAccount;
import ru.ravel.ItDesk.model.MessageFrom;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.EmailAccountRepository;
import ru.ravel.ItDesk.repository.MessageRepository;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;


@Service
public class EmailService {

	private final ClientService clientService;
	private final ClientRepository clientRepository;
	private final MessageRepository messageRepository;
	private final EmailAccountRepository emailAccountRepository;

	private final Map<EmailAccount, Store> imapStores = new ConcurrentHashMap<>();
	private final Map<EmailAccount, JavaMailSender> smtpSenders = new ConcurrentHashMap<>();

	private final Logger logger = LoggerFactory.getLogger(this.getClass());


	public EmailService(@Lazy ClientService clientService, ClientRepository clientRepository,
						MessageRepository messageRepository, EmailAccountRepository emailAccountRepository) {
		this.clientService = clientService;
		this.clientRepository = clientRepository;
		this.messageRepository = messageRepository;
		this.emailAccountRepository = emailAccountRepository;
		emailAccountRepository.findAll().forEach(this::addMailAccount);
	}


	public List<EmailAccount> getEmailsAccounts() {
		return emailAccountRepository.findAll();
	}


	public EmailAccount newEmailAccount(EmailAccount emailAccount) {
		addMailAccount(emailAccount);
		return emailAccountRepository.save(emailAccount);
	}


	public EmailAccount updateEmailAccount(EmailAccount emailAccount) {
		return newEmailAccount(emailAccount);
	}


	public void deleteEmailAccount(Long emailId) {
		emailAccountRepository.deleteById(emailId);
	}


	public void sendEmail(@NotNull ru.ravel.ItDesk.model.Message message, @NotNull Client client) {
		EmailAccount emailAccount = client.getEmailAccountSender();
		JavaMailSender mailSender = smtpSenders.get(emailAccount);
		SimpleMailMessage emailMessage = new SimpleMailMessage();
		emailMessage.setTo(client.getEmail());
		emailMessage.setSubject(emailAccount.getSubject());
		emailMessage.setText(message.getText());
		emailMessage.setFrom(emailAccount.getEmailFrom());
		mailSender.send(emailMessage);
	}


	@Async
	@Scheduled(fixedRate = 3, timeUnit = TimeUnit.SECONDS)
	public void checkEmails() {
		imapStores.forEach((emailAccount, store) -> {
			try {
				receiveEmails(store, emailAccount);
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
			}
		});
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
				List<Client> clients = clientService.getClients().stream().filter(c -> c.getEmail() != null).toList();
				String emailFrom = ((InternetAddress) emailMessage.getFrom()[0]).getAddress();
				ru.ravel.ItDesk.model.Message message = ru.ravel.ItDesk.model.Message.builder()
						.text(getTextFromMessage(emailMessage))
						.date(ZonedDateTime.ofInstant(Instant.ofEpochMilli(emailMessage.getReceivedDate().getTime()), ZoneId.systemDefault()))
						.isComment(false)
						.isRead(false)
						.isSent(false)
						.build();
				messageRepository.save(message);
				Client client = clients.stream().filter(c -> c.getEmail().equals(emailFrom)).findFirst().orElse(null);
				if (client != null) {
					client.getMessages().add(message);
				} else {
					client = Client.builder()
							.email(emailFrom)
							.firstname(emailFrom)
							.messageFrom(MessageFrom.EMAIL)
							.messages(List.of(message))
							.emailAccountSender(emailAccount)
							.build();
				}
				clientRepository.save(client);
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

}