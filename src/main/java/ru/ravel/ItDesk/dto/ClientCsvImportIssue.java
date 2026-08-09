package ru.ravel.ItDesk.dto;

public record ClientCsvImportIssue(
		int row,
		String level,
		String message
) {
}
