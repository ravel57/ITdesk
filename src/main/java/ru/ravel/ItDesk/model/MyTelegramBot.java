package ru.ravel.ItDesk.model;

import com.pengrad.telegrambot.TelegramBot;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MyTelegramBot {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	//	@JsonIgnore
	private String token;

	@Transient
	private TelegramBot bot;

	public void initBot() {
		bot= new TelegramBot(token);
	}
}
