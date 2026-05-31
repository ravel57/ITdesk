package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.ZonedDateTime;


@Data
@AllArgsConstructor
public class FileDto {
	private String uuid;
	private String name;
	private String type;
	private ZonedDateTime date;
}