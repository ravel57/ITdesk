package ru.ravel.ItDesk.Service.Interfaces;

import ru.ravel.ItDesk.Models.Message;

import java.util.List;

public interface MessageServiceInterface {
    void saveMessage(Message message);
    void saveReplyMessage(Message message);
    List<Message> getUsersMessages (long telegramId);
    void sendMessagesToBot(Message Message);
    void sendMessagesToFront(Message message);
}
