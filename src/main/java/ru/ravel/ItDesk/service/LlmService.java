package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.LlmRequestBody;
import ru.ravel.ItDesk.dto.LlmResponse;
import ru.ravel.ItDesk.feign.LlmFeignClient;


@Service
@RequiredArgsConstructor
public class LlmService {

	private final LlmFeignClient llmFeignClient;


	public String askLlm(String question) {
		LlmRequestBody body = LlmRequestBody.builder().query(question).build();
		LlmResponse response = llmFeignClient.askQuery(body);
		return response.getAnswer();
	}

}