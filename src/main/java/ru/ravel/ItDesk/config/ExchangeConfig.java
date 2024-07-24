package ru.ravel.ItDesk.config;

import microsoft.exchange.webservices.data.core.ExchangeService;
import microsoft.exchange.webservices.data.core.enumeration.misc.ExchangeVersion;
import microsoft.exchange.webservices.data.credential.WebCredentials;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;

@Configuration
public class ExchangeConfig {

	@Bean
	public ExchangeService exchangeService() throws MalformedURLException, URISyntaxException {
		ExchangeService service = new ExchangeService(ExchangeVersion.Exchange2010_SP2);
		service.setCredentials(new WebCredentials("username", "password"));
		service.setUrl(new URL("https://outlook.office365.com/EWS/Exchange.asmx").toURI());
		return service;
	}
}