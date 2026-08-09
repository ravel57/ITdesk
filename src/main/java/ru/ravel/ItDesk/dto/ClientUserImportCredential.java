package ru.ravel.ItDesk.dto;

public record ClientUserImportCredential(
        String username,
        String password,
        String firstname,
        String lastname,
        String organization
) {
}
