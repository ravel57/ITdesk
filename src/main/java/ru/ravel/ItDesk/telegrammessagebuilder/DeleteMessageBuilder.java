package ru.ravel.ItDesk.telegrammessagebuilder;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.DeleteMessage;

public class DeleteMessageBuilder extends MessageBuilder {

	private Integer messageId;


	public DeleteMessageBuilder(TelegramBot bot) {
		super(bot);
	}

	public DeleteMessageBuilder telegramId(Long telegramId) {
		this.telegramId = telegramId;
		return this;
	}

	public DeleteMessageBuilder messageId(Integer messageId) {
		this.messageId = messageId;
		return this;
	}

	public void execute() throws NoSuchFieldException {
		if (telegramId == null || this.messageId == null) {
			throw new NoSuchFieldException();
		}
		var message = new DeleteMessage(telegramId, this.messageId);

		bot.execute(message);
	}

}