package ru.ravel.ItDesk.DAO.Interfaces;

import ru.ravel.ItDesk.Models.Message;

import java.util.List;

public interface MessageDAOInterface {
    void saveClientMessage(Message messege);
    void saveSupportMessage(Message messege);
    List<Message> getUsersMessages(long telegramId);
}
