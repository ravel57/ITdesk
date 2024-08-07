package ru.ravel.ItDesk.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@FeignClient(name = "resetPassword", url = "${license-server-url}")
public interface ResetPasswordFeignClient {

	@PostMapping("/api/v1/reset-password")
	String resetPassword(@RequestParam String emailToReset, @RequestParam UUID license);

}
