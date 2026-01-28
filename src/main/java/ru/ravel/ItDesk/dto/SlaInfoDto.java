package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.ZonedDateTime;


@Data
@AllArgsConstructor
public class SlaInfoDto {
	Boolean paused;
	ZonedDateTime deadline;
	Long remainingSeconds;
	Long pausedSeconds;
}