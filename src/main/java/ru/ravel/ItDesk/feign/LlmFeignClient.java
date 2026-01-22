package ru.ravel.ItDesk.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import ru.ravel.ItDesk.dto.LlmRequestBody;
import ru.ravel.ItDesk.dto.LlmResponse;


@FeignClient(name = "llm", url = "${app.llm-url}")
public interface LlmFeignClient {

	@PostMapping("/ask")
	LlmResponse askQuery(@RequestBody LlmRequestBody body);

}