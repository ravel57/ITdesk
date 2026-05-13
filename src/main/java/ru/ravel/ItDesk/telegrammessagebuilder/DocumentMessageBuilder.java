package ru.ravel.ItDesk.telegrammessagebuilder;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendDocument;
import com.pengrad.telegrambot.response.SendResponse;

import java.io.File;

public class DocumentMessageBuilder extends MessageBuilder {

	private File file;
	private Integer replyMessageId;


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

	public DocumentMessageBuilder replyMessage(Integer replyMessageId) {
		this.replyMessageId = replyMessageId;
		return this;
	}

	public Integer execute() throws NoSuchFieldException {
		if (telegramId == null || file == null) {
			throw new NoSuchFieldException();
		}
		SendDocument message = new SendDocument(telegramId, file);
		if (replyMessageId != null) {
			message.replyToMessageId(replyMessageId);
		}

		SendResponse response = bot.execute(message);
		if (response.isOk()) {
			return response.message().messageId();
		} else {
			throw new RuntimeException(response.description());
		}
	}
}
