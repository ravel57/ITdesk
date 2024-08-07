package ru.ravel.ItDesk.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.ravel.ItDesk.model.License;


@FeignClient(name = "license", url = "${license-server-url}")
public interface LicenseFeignClient {

	@PostMapping(value = "/api/v1/register")
	License register(@RequestParam String name);

	@GetMapping(value = "/api/v1/license/{license}")
	License license(@PathVariable String license);

}
