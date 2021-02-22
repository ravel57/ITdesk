package ru.ravel.ItDesk.DAO.Interfaces;

import ru.ravel.ItDesk.Models.Message;

import java.util.List;

public interface MessageDAOInterface {
    void saveMessage(String clientID, String messege);
    List<Message> getUsersMessages(String telegramId);
}
