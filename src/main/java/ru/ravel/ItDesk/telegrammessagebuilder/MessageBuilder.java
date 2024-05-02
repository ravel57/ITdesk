package ru.ravel.ItDesk.telegrammessagebuilder;

import com.pengrad.telegrambot.TelegramBot;

public class MessageBuilder {
	protected final TelegramBot bot;
	protected Long telegramId;

	public MessageBuilder(TelegramBot bot) {
		this.bot = bot;
	}

	public MessageBuilder telegramId(Long telegramId) {
		this.telegramId = telegramId;
		return this;
	}

	public SendMessageBuilder send() {
		return new SendMessageBuilder(bot);
	}

	public EditMessageBuilder edit() {
		return new EditMessageBuilder(bot);
	}

	public DeleteMessageBuilder delete() {
		return new DeleteMessageBuilder(bot);
	}

}
