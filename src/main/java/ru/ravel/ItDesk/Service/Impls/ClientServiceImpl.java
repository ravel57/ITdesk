package ru.ravel.ItDesk.Service.Impls;

import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.DAO.Interfaces.ClientDAOInterface;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.ClientTask;
import ru.ravel.ItDesk.Service.Interfaces.ClientServiceInterface;

import java.util.List;


@Service
public class ClientServiceImpl implements ClientServiceInterface {

    private final ClientDAOInterface clientDAOInterface;
    public ClientServiceImpl(ClientDAOInterface clientDAOInterface) {
        this.clientDAOInterface = clientDAOInterface;
    }


    public List<Client> getAllClients() {
        return clientDAOInterface.getAllClients();
    }

    @Override
    public List<ClientTask> getActiveClients() {
        return clientDAOInterface.getActiveClients();
    }

    @Override
    public Client getClientByTelegramId(long telegramId) {
        return clientDAOInterface.getClientByTelegramId(telegramId);
    }

    @Override
    public boolean checkRegisteredByTelegramId(long telegramId) {
        return (clientDAOInterface.getClientByTelegramId(telegramId).getId() != 0);
//        return clientDAOInterface.registered(telegram);
    }

    @Override
    public void addUser(Client client) {
        clientDAOInterface.addUser(client);
    }

    @Override
    public String getTelegramIdByClientId(long clientId) {
        return clientDAOInterface.getTelegramIdByClientId(clientId);
    }

    @Override
    public Client getClient(long id) {
        return clientDAOInterface.getClientById(id);
    }



}
