package ru.ravel.ItDesk.dto;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;

@Getter
@EqualsAndHashCode
public class MessageTask {
	private Message message;
	private Task task;
}
