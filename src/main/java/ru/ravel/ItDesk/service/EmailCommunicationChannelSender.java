package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.MessageFrom;

@Component
@RequiredArgsConstructor
public class EmailCommunicationChannelSender implements CommunicationChannelSender {

    private final EmailService emailService;

    @Override
    public MessageFrom channel() {
        return MessageFrom.EMAIL;
    }

    @Override
    public void send(Client client, Message message) throws Exception {
        emailService.sendEmail(message, client);
    }
}
