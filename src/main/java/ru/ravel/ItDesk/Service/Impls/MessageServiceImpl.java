package ru.ravel.ItDesk.Service.Impls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.Controllers.TelegramBotController;
import ru.ravel.ItDesk.DAO.Interfaces.MessageDAOInterface;
import ru.ravel.ItDesk.Models.Message;
import ru.ravel.ItDesk.Service.Interfaces.MessageServiceInterface;

import java.util.List;

@Service
public class MessageServiceImpl implements MessageServiceInterface {

    @Autowired
    private SimpMessagingTemplate template;
    @Autowired
    TelegramBotController bot;

    final private MessageDAOInterface messageDAOInterface;

    public MessageServiceImpl(MessageDAOInterface messageDAOInterface) {
        this.messageDAOInterface = messageDAOInterface;
    }

    @Override
    public void saveMessage(Message message) {
        messageDAOInterface.saveClientMessage(message);
    }

    @Override
    public void saveReplyMessage(Message message) {
        messageDAOInterface.saveReplyMessage(message);
    }

    @Override
    public List<Message> getUsersMessages(long telegramId) {
        return messageDAOInterface.getUsersMessages(telegramId);
    }

    @Override
    public void sendMessagesToBot(Message Message) {
        bot.sendMessage(Message);
    }

    public void sendMessagesToFront(Message message) {
        this.template.convertAndSend("/topic/messages", message);
    }
}
