package ru.ravel.ItDesk.service;

import jakarta.mail.Store;
import microsoft.exchange.webservices.data.core.ExchangeService;
import microsoft.exchange.webservices.data.core.enumeration.misc.ExchangeVersion;
import microsoft.exchange.webservices.data.core.enumeration.property.WellKnownFolderName;
import microsoft.exchange.webservices.data.core.enumeration.service.ConflictResolutionMode;
import microsoft.exchange.webservices.data.core.service.folder.Folder;
import microsoft.exchange.webservices.data.core.service.item.EmailMessage;
import microsoft.exchange.webservices.data.core.service.item.Item;
import microsoft.exchange.webservices.data.core.service.schema.EmailMessageSchema;
import microsoft.exchange.webservices.data.credential.WebCredentials;
import microsoft.exchange.webservices.data.search.FindItemsResults;
import microsoft.exchange.webservices.data.search.ItemView;
import microsoft.exchange.webservices.data.search.filter.SearchFilter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.EmailAccount;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
//@RequiredArgsConstructor
public class ExchangeEmailService {

	private final ExchangeService exchangeService = this.exchangeService();

	private final Map<EmailAccount, Store> exchangeStores = new ConcurrentHashMap<>();

	Logger logger = LoggerFactory.getLogger(this.getClass());


	public ExchangeEmailService() throws MalformedURLException, URISyntaxException {
	}

	@Async
	@Scheduled(fixedRate = 3, timeUnit = TimeUnit.SECONDS)
	public void checkEmails() {
		exchangeStores.forEach((emailAccount, store) -> {
			try {
//				receiveEmails(store, emailAccount);
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
			}
		});
	}

	@Bean
	public ExchangeService exchangeService() throws MalformedURLException, URISyntaxException {
		ExchangeService service = new ExchangeService(ExchangeVersion.Exchange2010_SP2);
		service.setCredentials(new WebCredentials("username", "password"));
		service.setUrl(new URL("https://outlook.office365.com/EWS/Exchange.asmx").toURI());
		return service;
	}

//	public void EmailAccountConfig(String username, String password, String domain, URI exchangeUrl) {
//		this.username = username;
//		this.password = password;
//		this.domain = domain;
//		this.exchangeUrl = exchangeUrl;
//	}


	public List<EmailMessage> getUnreadEmails() throws Exception {
		Folder inbox = Folder.bind(exchangeService, WellKnownFolderName.Inbox);
		SearchFilter unreadFilter = new SearchFilter.IsEqualTo(EmailMessageSchema.IsRead, false);
		ItemView view = new ItemView(100);
		FindItemsResults<Item> findResults = exchangeService.findItems(inbox.getId(), unreadFilter, view);
		List<EmailMessage> unreadEmails = new ArrayList<>();
		for (Item item : findResults) {
			if (item instanceof EmailMessage email) {
				email.load();
				unreadEmails.add(email);
			}
		}
		return unreadEmails;
	}

	public void markEmailsAsRead(@NotNull List<EmailMessage> emails) throws Exception {
		for (EmailMessage email : emails) {
			email.setIsRead(true);
			email.update(ConflictResolutionMode.AlwaysOverwrite);
		}
	}
}
