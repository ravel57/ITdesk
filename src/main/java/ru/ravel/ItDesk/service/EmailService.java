package ru.ravel.ItDesk.service;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.FlagTerm;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EmailService {

	private final ClientService clientService;
	private final ClientRepository clientRepository;
	private final MessageRepository messageRepository;
	private final JavaMailSender mailSender;
	private final Store mailStore;
	private final Logger logger = LoggerFactory.getLogger(this.getClass());


	public void sendSimpleEmail(String toEmail, String subject, String body) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setTo(toEmail);
		message.setSubject(subject);
		message.setText(body);
		message.setFrom("");
		mailSender.send(message);
	}


	public void receiveEmails() {
		try {
			Folder emailFolder = mailStore.getFolder("INBOX");
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
							.sourceChannel("***")
							.messages(List.of(message))
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
