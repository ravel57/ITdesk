package ru.ravel.ItDesk.Service.Impls;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.DAO.Impls.ClientDAOImpl;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.ClientTask;
import ru.ravel.ItDesk.Service.Interfaces.ClientServiceInterface;

import java.util.List;


@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class ClientServiceImpl implements ClientServiceInterface {

    private final ClientDAOImpl clientsDAO;
    private final TaskServiceImpl tasks;
    private final SimpMessagingTemplate simpMessaging;


    @Override
    public void addClient(Client client) {
        clientsDAO.addClient(client);
    }

    public void changeClient(Client client) {
        clientsDAO.changeClient(client);
    }

    @Override
    public Client getClient(long id) {
        return clientsDAO.getClientById(id);
    }

    public List<Client> getAllClients() {
        return clientsDAO.getAllClients();
    }

    @Override
    public List<ClientTask> getActiveClients() {
        List<ClientTask> clientTasks = clientsDAO.getActiveClients(
                SecurityContextHolder.getContext().getAuthentication().getPrincipal()
        );
        for (ClientTask client : clientTasks) {
            client.setTasks(tasks.getClientActualTasks(client.getId()));
            client.setLastMessageDifTime(0L);
        }
        return clientTasks;
    }

    @Override
    public Client getClientByTelegramId(long telegramId) {
        return clientsDAO.getClientByTelegramId(telegramId);
    }

    @Override
    public String getTelegramIdByClientId(long clientId) {
        return clientsDAO.getTelegramIdByClientId(clientId);
    }

    @Override
    public boolean checkRegisteredByTelegramId(long telegramId) {
        return (clientsDAO.getClientByTelegramId(telegramId).getId() != 0);
    }

    public void sendClientToFront(Client client) {
        this.simpMessaging.convertAndSend("/topic/activity", client);
    }

}
