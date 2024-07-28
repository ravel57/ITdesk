package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import ru.ravel.ItDesk.model.Message;

import java.util.List;

@Getter
@AllArgsConstructor
public class LinkedMessagePage {
	private Integer page;
	private List<Message> messages;
	private Boolean isEnd;
}
