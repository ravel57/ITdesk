package ru.ravel.ItDesk.telegrammessagebuilder;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendDocument;
import com.pengrad.telegrambot.response.SendResponse;

import java.io.File;

public class DocumentMessageBuilder extends MessageBuilder {

	private File file;


	public DocumentMessageBuilder(TelegramBot bot) {
		super(bot);
	}

	public DocumentMessageBuilder telegramId(Long telegramId) {
		this.telegramId = telegramId;
		return this;
	}

	public DocumentMessageBuilder file(File file) {
		this.file = file;
		return this;
	}

	public Integer execute() throws NoSuchFieldException {
		if (telegramId == null || file == null) {
			throw new NoSuchFieldException();
		}
		SendDocument message = new SendDocument(telegramId, file);
		SendResponse response = bot.execute(message);
		if (response.isOk()) {
			return response.message().messageId();
		} else {
			throw new RuntimeException(response.description());
		}
	}
}
