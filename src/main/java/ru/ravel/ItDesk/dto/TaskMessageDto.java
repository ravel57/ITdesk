package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import ru.ravel.ItDesk.model.Message;


@Data
@AllArgsConstructor
public class TaskMessageDto {
	Long clientId;
	Long taskId;
	Message message;
}