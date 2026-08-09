package ru.ravel.ItDesk.service;

import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.MessageFrom;

/**
 * Adapter for one external communication channel.
 * Implementations must throw on delivery failure so the outbox event can be retried.
 */
public interface CommunicationChannelSender {

    MessageFrom channel();

    void send(Client client, Message message) throws Exception;
}
