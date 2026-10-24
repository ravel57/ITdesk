package ru.ravel.ItDesk.dto;

import jakarta.validation.constraints.NotNull;

public record ClientPortalNotificationSettingsDto(@NotNull Boolean notificationsEnabled) {}
