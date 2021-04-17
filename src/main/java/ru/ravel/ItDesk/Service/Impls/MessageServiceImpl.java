package ru.ravel.ItDesk.Service.Impls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.Controllers.TelegramBotController;
import ru.ravel.ItDesk.DAO.Impls.MessageDAOImpl;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Message;

import java.util.List;

@Service
public class MessageServiceImpl /*implements MessageServiceInterface*/ {

    @Autowired
    TelegramBotController bot;
    @Autowired
    MessageDAOImpl messageDAO;
    @Autowired
    private SimpMessagingTemplate template;


    //    @Override
    //todo get messageId from DB
    public void saveClientMessage(Message message) {
        messageDAO.saveClientMessage(message);
    }

    //    @Override
    public void saveSupportMessage(Message message) {
        messageDAO.saveSupportMessage(message);
    }

    //    @Override
    public List<Message> getClientsMessages(Client client) {
        return messageDAO.getClientsMessages(client);
    }

    //    @Override
    public void sendMessagesToBot(Message message) {
        bot.sendMessage(message);
    }

    public void sendMessagesToFront(Message message) {
        this.template.convertAndSend("/topic/activity", message);
    }
}
