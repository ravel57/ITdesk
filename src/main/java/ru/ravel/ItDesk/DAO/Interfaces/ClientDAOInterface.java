package ru.ravel.ItDesk.DAO.Interfaces;

import ru.ravel.ItDesk.Models.Client;

import java.util.List;

public interface ClientDAOInterface {
    List<Client> getAllUser();
    Client getUser(long id);
}
