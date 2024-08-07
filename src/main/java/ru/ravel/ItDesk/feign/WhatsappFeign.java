package ru.ravel.ItDesk.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.whatsapp.MessageResponse;
import ru.ravel.ItDesk.whatsapp.UpdateBody;
import ru.ravel.ItDesk.whatsapp.UpdateResponse;
import ru.ravel.ItDesk.whatsapp.WaRequestMessage;


@FeignClient(name = "whatsappFeign", url = "https://whatsgate.ru/api/v1")
public interface WhatsappFeign {

	@PostMapping("/send")
	MessageResponse sendMessage(@RequestHeader("X-Api-Key") String s, @RequestBody WaRequestMessage message);

	@PostMapping("events-get")
	UpdateResponse getUpdate(@RequestHeader("X-Api-Key") String s, @RequestBody UpdateBody updateBody);

	@PostMapping("/typing")
	Object postTyping();

}
