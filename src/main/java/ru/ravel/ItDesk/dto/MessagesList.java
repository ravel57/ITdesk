package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import ru.ravel.ItDesk.model.Message;

import java.util.List;

@AllArgsConstructor
public class MessagesList {
	List<Message> messages;
	Boolean isEnd;
}
