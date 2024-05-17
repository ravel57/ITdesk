package ru.ravel.ItDesk.dto;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.User;

@Getter
@EqualsAndHashCode
public class ClientUserText {
	private Client client;
	private User user;
	private String text;
}
