package ru.ravel.ItDesk.Service.Impls;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.Controllers.TelegramBotController;
import ru.ravel.ItDesk.DAO.Impls.ClientDAOImpl;
import ru.ravel.ItDesk.DAO.Impls.MessageDAOImpl;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Message;

import java.util.List;

@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class MessageServiceImpl /*implements MessageServiceInterface*/ {

    private final TelegramBotController bot;
    private final MessageDAOImpl messageDAO;
    private final SimpMessagingTemplate template;
    private final ClientDAOImpl clientsDAO;


    //    @Override
    //todo get messageId from DB
    public void saveClientMessage(Message message) {
        messageDAO.saveClientMessage(message);
    }

    public void markChatUnread(Message message) {
        messageDAO.markChatUnread(message.getClientId());
    }

    public void markChatRead(Object supportId, long clientId) {
        messageDAO.markChatRead(supportId, clientId);
    }

    //    @Override
    public void saveSupportMessage(Message message) {
        messageDAO.saveSupportMessage(message);
    }

    //    @Override
    public List<Message> getClientsMessagesById(long clientId) {
        List<Message> messages = messageDAO.getClientsMessages(clientId);
        for (int i = 0; i < messages.size(); i++)
            messages.get(i).setId(i);
        return messages;
    }

    //    @Override
    public long getClientsMessagesCount(Client client) {
        return messageDAO.getClientsMessagesCount(client);
    }

    //    @Override
    public void sendMessagesToBot(Message message) {
        bot.sendMessage(message);
    }

    public void sendMessagesToFront(Message message) {
        this.template.convertAndSend("/topic/activity", message);
    }
}
