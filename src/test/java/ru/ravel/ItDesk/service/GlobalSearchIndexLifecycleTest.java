package ru.ravel.ItDesk.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.ravel.ItDesk.config.ElasticsearchConfig;
import ru.ravel.ItDesk.dto.GlobalSearchDocument;
import ru.ravel.ItDesk.model.Service;
import ru.ravel.ItDesk.repository.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/**
 * Жизненный цикл индекса глобального поиска на живом Elasticsearch (localhost:9200).
 * Без запущенного Elasticsearch тесты пропускаются.
 */
class GlobalSearchIndexLifecycleTest {

	private RestClient restClient;
	private ElasticsearchClient client;
	private ServiceRepository serviceRepository;
	private GlobalSearchService service;
	private String alias;


	@BeforeEach
	void setUp() throws IOException {
		restClient = RestClient.builder(new HttpHost("localhost", 9200, "http")).build();
		client = new ElasticsearchConfig().elasticsearchClient(restClient, new ObjectMapper());
		boolean available;
		try {
			available = client.ping().value();
		} catch (Exception e) {
			available = false;
		}
		assumeTrue(available, "Elasticsearch на localhost:9200 не запущен");

		alias = "uldesk_test_search_" + UUID.randomUUID().toString().substring(0, 8);
		serviceRepository = mock(ServiceRepository.class);
		service = new GlobalSearchService(client, mock(ClientRepository.class), mock(TaskRepository.class),
				mock(UserRepository.class), mock(KnowledgeService.class), mock(MessageRepository.class), serviceRepository);
		ReflectionTestUtils.setField(service, "indexName", alias);
	}


	@AfterEach
	void tearDown() throws IOException {
		if (alias != null) {
			// Удаление по маске в Elasticsearch 8 запрещено (destructive_requires_name), удаляем по именам
			for (String index : client.indices().get(request -> request.index(alias + "*")).result().keySet()) {
				client.indices().delete(request -> request.index(index));
			}
		}
		restClient.close();
	}


	@Test
	void firstWriteCreatesIndexUnderAliasWithOurMapping() throws IOException {
		service.indexService(catalogService(1L, "1С"));

		List<String> indices = aliasTargets();
		assertThat(indices).hasSize(1);
		assertThat(indices.getFirst()).startsWith(alias + "_");
		String entityType = client.indices().getMapping(request -> request.index(alias)).result()
				.get(indices.getFirst()).mappings().properties().get("entityType")._kind().jsonValue();
		assertThat(entityType).isEqualTo("keyword");
		assertThat(count()).isEqualTo(1);
	}


	@Test
	void reindexReplacesLegacyIndexWithAliasAtomically() throws IOException {
		// Индекс со старой схемой: имя алиаса занято обычным индексом
		client.indices().create(request -> request.index(alias));
		client.index(request -> request.index(alias).id("SERVICE:99").document(Map.of("title", "старый")));
		when(serviceRepository.findAll()).thenReturn(List.of(catalogService(1L, "1С"), catalogService(2L, "Почта")));

		Map<String, Object> result = service.reindexAll();

		assertThat(result.get("count")).isEqualTo(2L);
		assertThat(aliasTargets()).containsExactly((String) result.get("physicalIndex"));
		assertThat(count()).isEqualTo(2);
	}


	@Test
	void repeatedReindexKeepsSingleIndex() throws IOException {
		when(serviceRepository.findAll()).thenReturn(List.of(catalogService(1L, "1С")));

		service.reindexAll();
		Map<String, Object> second = service.reindexAll();

		List<String> ours = new ArrayList<>(client.indices().get(request -> request.index(alias + "_*")).result().keySet());
		assertThat(ours).containsExactly((String) second.get("physicalIndex"));
		assertThat(aliasTargets()).containsExactly((String) second.get("physicalIndex"));
	}


	@Test
	void changesMadeDuringReindexAreNotLost() throws IOException {
		Service late = catalogService(3L, "Сервис, созданный во время переиндексации");
		service.indexService(catalogService(1L, "1С"));
		// Снимок БД уже прочитан без сервиса 3, а он появляется и индексируется, пока новый индекс собирается
		when(serviceRepository.findAll()).thenAnswer(invocation -> {
			service.indexService(late);
			service.deleteService(1L);
			return List.of(catalogService(1L, "1С"), catalogService(2L, "Почта"));
		});

		service.reindexAll();

		assertThat(documentIds()).containsExactlyInAnyOrder("SERVICE:2", "SERVICE:3");
	}


	@Test
	void writesDuringReindexNeverFail() throws Exception {
		List<Service> stored = new ArrayList<>(List.of(catalogService(1L, "1С"), catalogService(2L, "Почта")));
		for (long id = 10; id < 15; id++) {
			stored.add(catalogService(id, "Сервис " + id));
		}
		when(serviceRepository.findAll()).thenReturn(stored);
		service.reindexAll();
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			List<Future<?>> writes = new ArrayList<>();
			for (int i = 0; i < 40; i++) {
				long id = 10 + i % 5;
				writes.add(executor.submit(() -> service.indexService(catalogService(id, "Сервис " + id))));
			}
			Future<Map<String, Object>> reindex = executor.submit(service::reindexAll);
			for (Future<?> write : writes) {
				write.get();
			}
			reindex.get();
		} finally {
			executor.shutdownNow();
		}

		assertThat(aliasTargets()).hasSize(1);
		assertThat(documentIds()).contains("SERVICE:1", "SERVICE:2", "SERVICE:10", "SERVICE:14");
	}


	private Service catalogService(Long id, String name) {
		Service catalogService = new Service();
		catalogService.setId(id);
		catalogService.setName(name);
		catalogService.setCode("S" + id);
		return catalogService;
	}


	private List<String> aliasTargets() throws IOException {
		return new ArrayList<>(client.indices().getAlias(request -> request.name(alias)).result().keySet());
	}


	private long count() throws IOException {
		client.indices().refresh(request -> request.index(alias));
		return client.count(request -> request.index(alias)).count();
	}


	private List<String> documentIds() throws IOException {
		client.indices().refresh(request -> request.index(alias));
		return client.search(request -> request.index(alias).size(100), GlobalSearchDocument.class)
				.hits().hits().stream().map(hit -> hit.id()).toList();
	}

}
