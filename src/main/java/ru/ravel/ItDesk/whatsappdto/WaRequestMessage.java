package ru.ravel.ItDesk.whatsappdto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WaRequestMessage {

	@JsonProperty("WhatsappID")
	public String whatsappID;

	public boolean async = false;

	public Recipient recipient;

	public MessageBody message;

}