package ru.ravel.ItDesk.dto;


import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ForceLogoutDto {
	String username;
	String sessionId;
}