package ru.ravel.ItDesk.dto;

public record ClientUserCsvPreviewRow(
        int rowNumber,
        boolean selected,
        String username,
        String firstname,
        String lastname,
        String email,
        String phoneNumber,
        String organization,
        String moreInfo,
        String status,
        String message
) {
}
