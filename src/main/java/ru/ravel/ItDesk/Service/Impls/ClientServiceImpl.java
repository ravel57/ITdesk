package ru.ravel.ItDesk.Service.Impls;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.User;
import ru.ravel.ItDesk.DAO.Impls.ClientDAOImpl;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.ClientTask;

import java.util.ArrayList;
import java.util.List;


@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class ClientServiceImpl /*implements ClientServiceInterface*/ {

    private final ClientDAOImpl clientsDAO;
    private final TaskServiceImpl tasks;
    private final SimpMessagingTemplate simpMessaging;
    private  List<Long> idsLocalCash = new ArrayList<>();

//    @Override
    public void addClient(Client client) {
        clientsDAO.addClient(client);
    }

    public void changeClient(Client client) {
        clientsDAO.changeClient(client);
    }

//    @Override
    public Client getClient(long id) {
        return clientsDAO.getClientById(id);
    }

//    public List<Client> getAllClients() {
//        return clientsDAO.getAllClients();
//    }

//    @Override
    public List<ClientTask> getActiveClients() {
        long supportId = (long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
//        List<Long> allClientsIds = clientsDAO.getAllClients().stream().map(Client::getId).collect(Collectors.toList());
        List<Long> allClientsIds = clientsDAO.getClientIds();
        List<Long> clientsWithRead = clientsDAO.getClientReadForSupport(supportId);
        for(long clientId : allClientsIds)
            if(!clientsWithRead.contains(clientId))
                clientsDAO.addReadStatus(supportId, clientId);

        List<ClientTask> clientTasks = clientsDAO.getActiveClientsWithReadedStatusForCurrentSupport(supportId);
        for (ClientTask client : clientTasks) {
            client.setTasks(tasks.getClientActualTasksById(client.getId()));
            client.setLastMessageDifTime(0L);
        }
        return clientTasks;
    }

//    @Override
    public Client getClientByTelegramUser(User user) {
        Client client;
        long telegramId = user.getId();
        if (idsLocalCash.contains(telegramId)) {
            client = clientsDAO.getClientByTelegramId(telegramId);
        } else {
            if (this.checkRegisteredByTelegramId(telegramId)) {
                client = clientsDAO.getClientByTelegramId(telegramId);
            } else {
                client = Client.builder()
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .userName(user.getUserName())
                        .telegramId(telegramId)
                        .build();
                clientsDAO.addClient(client);
                client.setId(clientsDAO.getClientByTelegramId(telegramId).getId());
            }
            idsLocalCash.add(telegramId);
            // todo delete asynchronously when idle time is exceeded
        }
        return client;
    }

//    @Override
    public String getTelegramIdByClientId(long clientId) {
        return clientsDAO.getTelegramIdByClientId(clientId);
    }

//    @Override
    public boolean checkRegisteredByTelegramId(long telegramId) {
        return (clientsDAO.getClientByTelegramId(telegramId).getId() != 0);
    }

    public void sendClientToFront(Client client) {
        this.simpMessaging.convertAndSend("/topic/activity", client);
    }

}
