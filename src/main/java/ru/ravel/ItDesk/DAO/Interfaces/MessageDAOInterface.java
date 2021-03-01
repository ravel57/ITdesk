package ru.ravel.ItDesk.DAO.Interfaces;

import ru.ravel.ItDesk.Models.Message;
import ru.ravel.ItDesk.Models.ReplayMessage;

import java.util.List;

public interface MessageDAOInterface {
    void saveMessage(Message messege);
    void saveReplyMessage(ReplayMessage messege);
//    List<Message> getUsersMessages(long telegramId);
    List<Message> getUsersMessages(long telegramId);
}
