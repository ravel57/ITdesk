package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import microsoft.exchange.webservices.data.core.ExchangeService;
import microsoft.exchange.webservices.data.core.enumeration.property.WellKnownFolderName;
import microsoft.exchange.webservices.data.core.enumeration.service.ConflictResolutionMode;
import microsoft.exchange.webservices.data.core.service.folder.Folder;
import microsoft.exchange.webservices.data.core.service.item.EmailMessage;
import microsoft.exchange.webservices.data.core.service.item.Item;
import microsoft.exchange.webservices.data.core.service.schema.EmailMessageSchema;
import microsoft.exchange.webservices.data.search.FindItemsResults;
import microsoft.exchange.webservices.data.search.ItemView;
import microsoft.exchange.webservices.data.search.filter.SearchFilter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExchangeEmailService {

	private final ExchangeService exchangeService;

	public List<EmailMessage> getUnreadEmails() throws Exception {
		Folder inbox = Folder.bind(exchangeService, WellKnownFolderName.Inbox);
		SearchFilter unreadFilter = new SearchFilter.IsEqualTo(EmailMessageSchema.IsRead, false);
		ItemView view = new ItemView(100);
		FindItemsResults<Item> findResults = exchangeService.findItems(inbox.getId(), unreadFilter, view);
		List<EmailMessage> unreadEmails = new ArrayList<>();
		for (Item item : findResults) {
			if (item instanceof EmailMessage) {
				EmailMessage email = (EmailMessage) item;
				email.load();
				unreadEmails.add(email);
			}
		}
		return unreadEmails;
	}

	public void markEmailsAsRead(List<EmailMessage> emails) throws Exception {
		for (EmailMessage email : emails) {
			email.setIsRead(true);
			email.update(ConflictResolutionMode.AlwaysOverwrite);
		}
	}
}
