package ru.ravel.ItDesk.dto;

import lombok.Getter;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.User;

@Getter
public class ClientUser {
	private Client client;
	private User user;
}
