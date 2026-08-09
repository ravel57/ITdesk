package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import ru.ravel.ItDesk.model.Message;


@AllArgsConstructor
@Getter
public class ObserverClientEventDto {
	private boolean observerClientEvent;
	private String eventType;
	private Long clientId;
	private Message message;
}
