package ru.ravel.ItDesk.whatsapp;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class MediaRequestBody {

	@JsonProperty("WhatsappID")
	public String whatsappID;

	public String mediaKey;

}
