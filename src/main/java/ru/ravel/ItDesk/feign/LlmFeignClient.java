package ru.ravel.ItDesk.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import ru.ravel.ItDesk.dto.LlmGenerateRequest;
import ru.ravel.ItDesk.dto.LlmGenerateResponse;
import ru.ravel.ItDesk.dto.LlmKbCountResponse;
import ru.ravel.ItDesk.dto.LlmRequestBody;
import ru.ravel.ItDesk.dto.LlmResponse;
import ru.ravel.ItDesk.dto.LlmRetrainRequest;
import ru.ravel.ItDesk.dto.LlmRetrainResponse;

@FeignClient(name = "llm", url = "${app.llm-url}")
public interface LlmFeignClient {

	@PostMapping("/ask")
	LlmResponse askQuery(@RequestBody LlmRequestBody body);

	/** Свободная генерация по заданию — не связана со статьями базы знаний. */
	@PostMapping("/generate")
	LlmGenerateResponse generate(@RequestBody LlmGenerateRequest body);

	@PostMapping("/retrain")
	LlmRetrainResponse retrain(@RequestBody LlmRetrainRequest body);

	@GetMapping("/kb/count")
	LlmKbCountResponse kbCount(@RequestParam("instance_name") String instanceName);
}