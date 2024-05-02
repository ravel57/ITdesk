package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class WebController {

	@GetMapping("")
	public String rootMapping() {
		return "index";
	}

}