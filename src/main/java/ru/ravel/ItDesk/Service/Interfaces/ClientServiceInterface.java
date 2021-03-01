package ru.ravel.ItDesk.Service.Interfaces;


import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.ClientTask;

import java.util.List;

public interface ClientServiceInterface {
    Client getClient(long id);
    List<Client> getAllClients();
    List<ClientTask> getActiveClients();
    Client getClientByTelegramId(long telegramId);
    boolean checkRegisteredByTelegramId(long telegramId);

    void addUser(Client client);
    String getTelegramIdByClientId(long clientId);

}
