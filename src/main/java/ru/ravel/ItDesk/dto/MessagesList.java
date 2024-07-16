package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import ru.ravel.ItDesk.model.Message;

import java.util.List;

@AllArgsConstructor
@Getter
public class MessagesList {
	private List<Message> messages;
	private Boolean isEnd;
}
