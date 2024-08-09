package ru.ravel.ItDesk.whatsappdto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class MediaRequestBody {

	@JsonProperty("WhatsappID")
	public String whatsappID;

	public String mediaKey;

}
