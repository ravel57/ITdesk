package ru.ravel.ItDesk.config;

import jakarta.mail.Session;
import jakarta.mail.Store;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration
public class EmailConfig {

	@Bean
	public JavaMailSender getJavaMailSender() {
		JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
		mailSender.setHost("smtp.***");
		mailSender.setPort(465);
		mailSender.setUsername("***");
		mailSender.setPassword("***");

		Properties props = mailSender.getJavaMailProperties();
		props.put("mail.transport.protocol", "smtp");
		props.put("mail.smtp.auth", "true");
		props.put("mail.smtp.starttls.enable", "true");
		props.put("mail.smtp.socketFactory.port", "465");
		props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		props.put("mail.smtp.socketFactory.fallback", "false");
		props.put("mail.smtp.ssl.enable", "true");

		return mailSender;
	}

	@Bean
	public Store mailStore() throws Exception {
		Properties properties = new Properties();
		properties.put("mail.store.protocol", "imaps");
		properties.put("mail.imap.host", "***");
		properties.put("mail.imap.port", "993");
		properties.put("mail.imap.auth", "true");
		properties.put("mail.imap.starttls.enable", "true");
		properties.put("mail.imap.ssl.enable", "true");
		properties.put("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory");

		Session emailSession = Session.getDefaultInstance(properties);
		Store store = emailSession.getStore("imaps");
		store.connect("imap.***", "***", "***");

		return store;
	}
}