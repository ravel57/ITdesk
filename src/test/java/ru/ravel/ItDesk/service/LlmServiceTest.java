package ru.ravel.ItDesk.service;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.ravel.ItDesk.dto.LlmGenerateRequest;
import ru.ravel.ItDesk.dto.LlmGenerateResponse;
import ru.ravel.ItDesk.dto.LlmResponse;
import ru.ravel.ItDesk.feign.LlmFeignClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


class LlmServiceTest {

	private final LlmFeignClient client = mock(LlmFeignClient.class);
	private final LlmService service = new LlmService(client, mock(KnowledgeService.class));


	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(service, "instanceName", "test_mac");
	}


	@Test
	void generationUsesFreeGenerationEndpoint() {
		when(client.generate(any())).thenReturn(new LlmGenerateResponse("110: Не найдена база 1С"));

		assertThat(service.generate("Придумай название")).isEqualTo("110: Не найдена база 1С");

		verify(client).generate(new LlmGenerateRequest("Придумай название", "test_mac"));
		verify(client, never()).askQuery(any());
	}


	@Test
	void oldServiceWithoutGenerateFallsBackToAsk() {
		when(client.generate(any())).thenThrow(new FeignException.NotFound("Not Found", request(), null, Map.of()));
		LlmResponse answer = new LlmResponse();
		answer.setAnswer("ответ по базе знаний");
		when(client.askQuery(any())).thenReturn(answer);

		assertThat(service.generate("задание")).isEqualTo("ответ по базе знаний");
	}


	@Test
	void generationFailureIsNotHiddenByFallback() {
		when(client.generate(any())).thenThrow(new FeignException.BadGateway("generation failed", request(), null, Map.of()));

		assertThatThrownBy(() -> service.generate("задание")).isInstanceOf(FeignException.BadGateway.class);
		verify(client, never()).askQuery(any());
	}


	private static Request request() {
		return Request.create(Request.HttpMethod.POST, "/generate", Map.of(), new byte[0], StandardCharsets.UTF_8, null);
	}

}
