package ru.ravel.ItDesk.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
public class ElasticsearchConfig {

	@Bean
	public RestClient elasticsearchRestClient(@Value("${elasticsearch.url}") String url) {
		URI uri = URI.create(url);
		return RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme())).build();
	}

	@Bean
	public ElasticsearchClient elasticsearchClient(RestClient restClient, ObjectMapper objectMapper) {
		ObjectMapper elasticsearchObjectMapper = objectMapper.copy();
		elasticsearchObjectMapper.registerModule(new JavaTimeModule());
		elasticsearchObjectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper(elasticsearchObjectMapper));
		return new ElasticsearchClient(transport);
	}
}