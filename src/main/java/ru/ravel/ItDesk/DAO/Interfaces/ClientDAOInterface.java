package ru.ravel.ItDesk.DAO.Interfaces;

import ru.ravel.ItDesk.Models.Client;

import java.util.List;

public interface ClientDAOInterface {
    List<Client> getAllUser();

    Client getUser(long id);

    List<Client> getActiveClients();

    Client authorized(String telegramId) ;

    //void saveMessege(String clientID, String messege);
}
