package ru.ravel.ItDesk.DAO.Impls;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.Mappers.MessageMapper;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Message;

import java.util.List;

@Repository
public class MessageDAOImpl /*implements MessageDAOInterface*/ {

    private final JdbcTemplate jdbcTemplate;

    public MessageDAOImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }


    //    @Override
    public void saveClientMessage(Message message) {
        jdbcTemplate.update(
                "INSERT INTO messages (client_id, text, date_time, message_type, support_id) " +
                        "VALUES (?, ?, ?, 1, null);",
                message.getClientId(), message.getText(), new java.util.Date()
        );
    }

    //    @Override
    public void saveSupportMessage(Message message) {
        jdbcTemplate.update(
                "INSERT INTO messages (client_id, support_id, text, date_time, message_type) VALUES (?, ?, ?, ?, 2);",
                message.getClientId(), message.getSupportId(), message.getText(), new java.util.Date()
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

    public List<Message> getClientsMessages(Client client) {
        try {
            return jdbcTemplate.query(
                    "select messages.id, messages.client_id, messages.text, messages.date_time,\n" +
                            "       message_type.message_type as message_type, messages.support_id\n" +
                            "from messages\n" +
                            "left join clients on messages.client_id = clients.id\n" +
                            "left join message_type on message_type.id = messages.message_type\n" +
                            "where clients.id = ?\n" +
                            "order by date_time;",
                    new Object[]{client.getId()}, new MessageMapper()
            );
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

}
