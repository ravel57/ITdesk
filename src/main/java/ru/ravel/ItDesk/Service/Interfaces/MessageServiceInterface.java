package ru.ravel.ItDesk.Service.Interfaces;

import ru.ravel.ItDesk.Models.Message;

import java.util.List;

public interface MessageServiceInterface {
    void saveMessage(String clientID, String message);
    List<Message> getUsersMessages (String telegramId);
}
