package ru.ravel.ItDesk.Service.Interfaces;


import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.ClientTask;

import java.util.List;

public interface ClientServiceInterface {
    Client getUser(long id);
    List<Client> getAllClients();
    List<ClientTask> getActiveClients();
    Client getClientById(String telegramId);
    boolean registered(String telegramId);

    void addUser(Client client);
}
