package ru.ravel.ItDesk.Service.Impls;

import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.DAO.Interfaces.MessageDAOInterface;
import ru.ravel.ItDesk.Service.Interfaces.MessageServiceInterface;

@Service
public class MessageServiceImpl implements MessageServiceInterface {


    final private MessageDAOInterface messageDAOInterface;

    public MessageServiceImpl(MessageDAOInterface messageDAOInterface) {
        this.messageDAOInterface = messageDAOInterface;
    }

    @Override
    public void saveMessage(String clientID, String message) {
        messageDAOInterface.saveMessage(clientID, message);
    }
}
