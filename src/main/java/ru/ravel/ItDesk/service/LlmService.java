package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.LlmRequestBody;
import ru.ravel.ItDesk.dto.LlmResponse;
import ru.ravel.ItDesk.feign.LlmFeignClient;


@Service
@RequiredArgsConstructor
public class LlmService {

	private final LlmFeignClient llmFeignClient;

	@Value("${app.instance-name}")
	private String instanceName;

	public String askLlm(String question) {
		LlmRequestBody body = LlmRequestBody.builder().query(question).instanceName(instanceName).build();
		LlmResponse response = llmFeignClient.askQuery(body);
		return response.getAnswer();
	}

}