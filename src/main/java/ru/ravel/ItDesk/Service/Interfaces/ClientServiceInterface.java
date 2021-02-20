package ru.ravel.ItDesk.Service.Interfaces;


import ru.ravel.ItDesk.Models.Client;

import java.util.List;

public interface ClientServiceInterface {
    Client getUser(long id);
    List<Client> getAllClients();
    List<Client> getActiveClients();
}
