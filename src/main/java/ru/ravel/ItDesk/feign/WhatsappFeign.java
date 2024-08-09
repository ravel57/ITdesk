package ru.ravel.ItDesk.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.whatsappdto.*;


@FeignClient(name = "whatsappFeign", url = "https://whatsgate.ru/api/v1")
public interface WhatsappFeign {

	@PostMapping("/send")
	MessageResponse sendMessage(@RequestHeader("X-Api-Key") String s, @RequestBody WaRequestMessage message);

	@PostMapping("events-get")
	UpdateResponse getUpdate(@RequestHeader("X-Api-Key") String s, @RequestBody UpdateBody updateBody);

	@PostMapping("/get-media")
	MediaResponseBody getMedia(@RequestHeader("X-Api-Key") String s, @RequestBody MediaRequestBody mediaRequestBody);

	@PostMapping("/typing")
	Object postTyping();

}
