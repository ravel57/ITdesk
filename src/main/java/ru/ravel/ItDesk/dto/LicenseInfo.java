package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.ZonedDateTime;


@Data
@AllArgsConstructor
public class LicenseInfo {
	private Long employeesCount;
	private ZonedDateTime licenseUntil;
}
