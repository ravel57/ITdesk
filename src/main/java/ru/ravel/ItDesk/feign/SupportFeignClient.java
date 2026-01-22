package ru.ravel.ItDesk.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import ru.ravel.ItDesk.model.Message;

import java.util.UUID;


@FeignClient(name = "support", url = "${app.license-server-url}")
public interface SupportFeignClient {

	@PostMapping("/api/v1/support/{license}/new-message")
	void newMessage(@PathVariable UUID license, @RequestBody Message message);

	@PostMapping("/api/v1/support/{license}/reset-password")
	String resetPassword(@PathVariable UUID license, @RequestBody String emailToReset);

}
