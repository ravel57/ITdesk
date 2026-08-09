package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.MessageFrom;

@Component
@RequiredArgsConstructor
public class TelegramCommunicationChannelSender implements CommunicationChannelSender {

    private final TelegramService telegramService;

    @Override
    public MessageFrom channel() {
        return MessageFrom.TELEGRAM;
    }

    @Override
    public void send(Client client, Message message) throws Exception {
        telegramService.sendMessage(client, message);
    }
}
