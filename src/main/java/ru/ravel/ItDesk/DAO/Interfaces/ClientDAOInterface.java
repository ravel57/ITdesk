package ru.ravel.ItDesk.DAO.Interfaces;

import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.ClientTask;

import java.util.List;

public interface ClientDAOInterface {
    List<Client> getAllUser();

    Client getUser(long id);

    List<ClientTask> getActiveClients();

    Client getClientById(String telegramId);

//    boolean registered(String telegramId) ;

    void addUser(Client client);

    //void saveMessege(String clientID, String messege);
}
