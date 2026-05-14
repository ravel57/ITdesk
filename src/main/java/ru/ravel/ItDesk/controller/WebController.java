package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
@CrossOrigin
public class WebController {

	@GetMapping({
			"",
			"/",
			"/login",
			"/login-error",
			"/session-expired",
			"/chats",
			"/chats/**",
			"/tasks",
			"/tasks/**",
			"/settings",
			"/settings/**",
			"/my-tasks",
			"/chat",
			"/history",
			"/search",
			"/help",
			"/analytics"
	})
	public String spaMapping() {
		return "forward:/index.html";
	}
}