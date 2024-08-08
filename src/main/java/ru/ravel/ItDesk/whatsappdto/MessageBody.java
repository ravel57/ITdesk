package ru.ravel.ItDesk.whatsappdto;

import lombok.AllArgsConstructor;
import lombok.Builder;

@Builder
@AllArgsConstructor
public class MessageBody {
	public Type type;
	public String body;
	public String quote;
	public Media media;
}
