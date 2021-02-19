package ru.ravel.ItDesk.Service.Impls;

import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.DAO.Interfaces.ClientDAOInterface;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Service.Interfaces.ClientServiceInterface;

import java.util.List;


@Service
public class ClientServiceImpl implements ClientServiceInterface {

    private final ClientDAOInterface clientDAOInterface;

    public ClientServiceImpl(ClientDAOInterface clientDAOInterface) {
        this.clientDAOInterface = clientDAOInterface;
    }


    public List<Client> getAllClients() {
        return clientDAOInterface.getAllUser();
    }

    @Override
    public Client getUser(long id) {
        return clientDAOInterface.getUser(id);
    }

}
