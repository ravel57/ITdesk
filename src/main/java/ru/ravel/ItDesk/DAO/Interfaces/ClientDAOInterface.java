package ru.ravel.ItDesk.DAO.Interfaces;

import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.ClientTask;

import java.util.List;

public interface ClientDAOInterface {
    List<Client> getAllClients();

    Client getClientById(long id);

    List<ClientTask> getActiveClients();

    Client getClientByTelegramId(long telegramId);

    String getTelegramIdByClientId(long clientId);

//    boolean registered(String telegramId) ;

    void addUser(Client client);

    //void saveMessege(String clientID, String messege);
}
