package ru.ravel.ItDesk.dto;

import java.util.List;

public record ClientCsvImportResult(
		int totalRows,
		int created,
		int updated,
		int skipped,
		List<ClientCsvImportIssue> issues
) {
}
