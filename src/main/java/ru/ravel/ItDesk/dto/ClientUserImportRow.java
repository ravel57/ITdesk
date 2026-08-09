package ru.ravel.ItDesk.dto;

public record ClientUserImportRow(
        Integer rowNumber,
        Boolean selected,
        String username,
        String firstname,
        String lastname,
        String email,
        String phoneNumber,
        String organization,
        String moreInfo
) {
}
