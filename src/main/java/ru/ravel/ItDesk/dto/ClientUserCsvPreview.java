package ru.ravel.ItDesk.dto;

import java.util.List;

public record ClientUserCsvPreview(
        int totalRows,
        int readyRows,
        List<ClientUserCsvPreviewRow> rows
) {
}
