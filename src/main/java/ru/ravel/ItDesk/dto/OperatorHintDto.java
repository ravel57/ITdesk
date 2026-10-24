package ru.ravel.ItDesk.dto;

import ru.ravel.ItDesk.model.OperatorHint;


public record OperatorHintDto(Long id, Long noteMessageId, Long sourceMessageId, String source, String sourceLabel, Long sourceRefId,
                              String title, Double probability, Boolean helpful) {

	public static OperatorHintDto of(OperatorHint hint) {
		return new OperatorHintDto(hint.getId(), hint.getNoteMessageId(), hint.getSourceMessageId(), hint.getSource(), hint.getSourceLabel(),
				hint.getSourceRefId(), hint.getTitle(), hint.getProbability(), hint.getHelpful());
	}
}
