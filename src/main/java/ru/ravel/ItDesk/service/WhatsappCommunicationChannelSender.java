package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.MessageFrom;

@Component
@RequiredArgsConstructor
public class WhatsappCommunicationChannelSender implements CommunicationChannelSender {

    private final WhatsappService whatsappService;

    @Override
    public MessageFrom channel() {
        return MessageFrom.WHATSAPP;
    }

    @Override
    public void send(Client client, Message message) throws Exception {
        whatsappService.sendMessage(message, client);
    }
}
