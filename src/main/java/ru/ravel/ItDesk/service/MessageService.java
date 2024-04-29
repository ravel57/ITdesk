package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MessageService {

//    private final TelegramBotController bot;
//    private final SimpMessagingTemplate template;


    //    @Override
    //todo get messageId from DB
//    public void saveClientMessage(Message message) {
//        messageDAO.saveClientMessage(message);
//    }
//
//    public void markChatUnread(Message message) {
//        messageDAO.markChatUnread(message.getClientId());
//    }
//
//    public void markChatRead(Object supportId, long clientId) {
//        messageDAO.markChatRead(supportId, clientId);
//    }
//
//
//    public void saveSupportMessage(Message message) {
//        messageDAO.saveSupportMessage(message);
//    }
//
//
//    public List<Message> getClientsMessagesById(long clientId) {
//        List<Message> messages = messageDAO.getClientsMessages(clientId);
//        for (long i = 0; i < messages.size(); i++)
//            messages.get((int) i).setId(i);
//        return messages;
//    }
//
//    public long getClientsMessagesCount(Client client) {
//        return messageDAO.getClientsMessagesCount(client);
//    }

    //    @Override
//    public void sendMessagesToBot(Message message) {
//        bot.sendMessage(message);
//    }

//    public void sendMessagesToFront(Message message) {
//        this.template.convertAndSend("/topic/activity", message);
//    }
}
