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
                "INSERT INTO messages (client_id, support_id, text, date_time, message_type) VALUES (?, ?, ?, ?, ?);",
                message.getClientId(), message.getSupportId(), message.getText(), new java.util.Date(),
                jdbcTemplate.queryForObject(
                        "select id from message_type where message_type like ?",
                        new Object[]{message.getMessageType()},
                        long.class
                )
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

    public List<Message> getClientsMessages(long clientId) {
        try {
            return jdbcTemplate.query(
                    "select messages.id, messages.client_id, messages.text, messages.date_time,\n" +
                            "       message_type.message_type as message_type, messages.support_id\n" +
                            "from messages\n" +
                            "left join message_type on message_type.id = messages.message_type\n" +
                            "where messages.client_id = ?\n" +
                            "order by date_time;",
                    new Object[]{clientId}, new MessageMapper()
            );
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public long getClientsMessagesCount(Client client) {
        try {
            return jdbcTemplate.queryForObject(
                    "select count(messages.id)\n" +
                            "from messages\n" +
                            "left join clients on messages.client_id = clients.id\n" +
                            "where clients.id = ?;",
                    new Object[]{client.getId()}, long.class
            );
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public void markChatRead(Object supportId, long clientId) {
        jdbcTemplate.update(
                "update support_clients_read\n" +
                        "SET readed = 1\n" +
                        "WHERE  support_id = ? and clients_id = ?;",
                supportId, clientId);
    }

    public void markChatUnread(long clientId) {
        jdbcTemplate.update(
                "update support_clients_read\n" +
                        "SET readed = 0\n" +
                        "WHERE clients_id = ?;",
                clientId);
    }

}
