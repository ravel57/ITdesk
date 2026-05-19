package ru.ravel.ItDesk.dto;

import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
public class AnswerRequiredRequest {
	private AnswerRequired answerRequired;
	private List<Long> groupMessageIds = Collections.emptyList();
}