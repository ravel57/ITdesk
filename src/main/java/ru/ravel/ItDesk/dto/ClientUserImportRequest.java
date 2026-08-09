package ru.ravel.ItDesk.dto;

import java.util.List;

public record ClientUserImportRequest(
        List<ClientUserImportRow> rows
) {
}
