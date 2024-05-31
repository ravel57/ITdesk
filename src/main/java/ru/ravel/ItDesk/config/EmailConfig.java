package ru.ravel.ItDesk.config;

import jakarta.mail.Session;
import jakarta.mail.Store;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import ru.ravel.ItDesk.repository.EmailRepository;

import java.util.Properties;

@Configuration
@RequiredArgsConstructor
public class EmailConfig {

	private final EmailRepository emailRepository;

	@Bean
	public JavaMailSender getJavaMailSender() {
		JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
		mailSender.setHost(emailRepository.findAll().get(0).getSmtpServer());
		mailSender.setPort(emailRepository.findAll().get(0).getSmtpPort());
		mailSender.setUsername(emailRepository.findAll().get(0).getEmailFrom());
		mailSender.setPassword(emailRepository.findAll().get(0).getPassword());

		Properties props = mailSender.getJavaMailProperties();
		props.put("mail.transport.protocol", "smtp");
		props.put("mail.smtp.auth", "true");
		props.put("mail.smtp.starttls.enable", "true");
		props.put("mail.smtp.socketFactory.port", emailRepository.findAll().get(0).getSmtpPort().toString());
		props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		props.put("mail.smtp.socketFactory.fallback", "false");
		props.put("mail.smtp.ssl.enable", "true");

		return mailSender;
	}

	@Bean
	public Store mailStore() throws Exception {
		Properties properties = new Properties();
		properties.put("mail.store.protocol", "imaps");
		properties.put("mail.imap.host", emailRepository.findAll().get(0).getImapServer());
		properties.put("mail.imap.port", emailRepository.findAll().get(0).getImapPort().toString());
		properties.put("mail.imap.auth", "true");
		properties.put("mail.imap.starttls.enable", "true");
		properties.put("mail.imap.ssl.enable", "true");
		properties.put("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		Session emailSession = Session.getDefaultInstance(properties);
		Store store = emailSession.getStore("imaps");
		store.connect(emailRepository.findAll().get(0).getImapServer(),
				emailRepository.findAll().get(0).getEmailFrom(),
				emailRepository.findAll().get(0).getPassword());
		return store;
	}
}