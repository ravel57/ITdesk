package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;


@AllArgsConstructor
@Getter
public class ClientMessage {
	private Client client;
	private Message message;
}
