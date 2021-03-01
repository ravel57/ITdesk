package ru.ravel.ItDesk.Service.Interfaces;

import ru.ravel.ItDesk.Models.Message;
import ru.ravel.ItDesk.Models.ReplayMessage;

import java.util.List;

public interface MessageServiceInterface {
    void saveMessage(Message message);
    void saveReplyMessage(ReplayMessage message);
    List<Message> getUsersMessages (long telegramId);
    void sendMessagesToBot(ReplayMessage replyeMessage);
    void sendMessagesToFront(Message message);
}
