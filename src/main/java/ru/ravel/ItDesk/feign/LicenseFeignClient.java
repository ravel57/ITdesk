package ru.ravel.ItDesk.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.ravel.ItDesk.model.License;

import java.util.UUID;


@FeignClient(name = "license", url = "${app.license-server-url}")
public interface LicenseFeignClient {

	@PostMapping(value = "/api/v1/register")
	License register(@RequestParam String name);

	@GetMapping(value = "/api/v1/license/{license}")
	License license(@PathVariable UUID license);

	@PostMapping(value = "/api/v1/license/{license}/version")
	ResponseEntity<Object> sendVersion(@PathVariable UUID license, @RequestParam String version);

}
