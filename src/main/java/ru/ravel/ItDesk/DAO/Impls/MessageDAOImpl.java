package ru.ravel.ItDesk.DAO.Impls;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.DAO.Interfaces.MessageDAOInterface;
import ru.ravel.ItDesk.Mappers.MessageMapper;
import ru.ravel.ItDesk.Mappers.ReplyMessageMapper;
import ru.ravel.ItDesk.Models.Message;
import ru.ravel.ItDesk.Models.ReplayMessage;

import java.util.List;

@Repository
public class MessageDAOImpl implements MessageDAOInterface {

    private final JdbcTemplate jdbcTemplate;

    public MessageDAOImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }


    @Override
    public void saveMessage(Message message) {
        jdbcTemplate.update(
                "INSERT INTO messages (client_id, text, date_time) VALUES (?, ?, ?);",
                message.getClientId(), message.getText(), new java.util.Date()
        );
    }

    @Override
    public void saveReplyMessage(ReplayMessage replayMessage) {
        jdbcTemplate.update(
                "INSERT INTO reply_messages (client_id, support_id, text, date_time) VALUES (?, ?, ?, ?);",
                replayMessage.getClientId(), replayMessage.getSupportId(), replayMessage.getText(), new java.util.Date()
        );
    }

//    public List<Message> getUsersMessages(long telegramId) {
//        try {
//            return jdbcTemplate.query("select * from messages \n " +
//                            "left join clients on messages.client_id = clients.id \n " +
//                            "where clients.id = ? \n " +
//                            "order by date_time;",
//                    new Object[]{telegramId}, new MessageMapper()
//            );
//        } catch (Exception e) {
//            e.printStackTrace();
//            throw e;
//        }
//    }

    public List<Message> getUsersMessages(long telegramId) {
        try {
            return jdbcTemplate.query("select * from messages \n " +
                            "left join clients on messages.client_id = clients.id \n " +
                            "where clients.id = ? \n " +
                            "order by date_time;",
                    new Object[]{telegramId}, new MessageMapper()
            );
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

}
