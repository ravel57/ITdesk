package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pengrad.telegrambot.TelegramBot;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TgBot {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String name;

	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	@ToString.Exclude
	private String token;

	@Transient
	@JsonIgnore
	private TelegramBot bot;


	public TelegramBot getBot() {
		if (bot == null) {
			bot = new TelegramBot(token);
		}
		return bot;
	}
}
