package ru.ravel.ItDesk.dto;

import java.util.List;

public record ClientUserImportResult(
        int totalRows,
        int created,
        int skipped,
        List<ClientUserImportCredential> credentials,
        List<ClientCsvImportIssue> issues
) {
}
