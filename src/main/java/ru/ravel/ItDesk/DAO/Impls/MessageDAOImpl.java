package ru.ravel.ItDesk.DAO.Impls;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.DAO.Interfaces.MessageDAOInterface;
import ru.ravel.ItDesk.Mappers.ClientMapper;
import ru.ravel.ItDesk.Mappers.MessageMapper;
import ru.ravel.ItDesk.Models.Message;
import ru.ravel.ItDesk.Service.Interfaces.MessageServiceInterface;

import java.util.List;
import java.util.Optional;

@Repository
public class MessageDAOImpl implements MessageDAOInterface {

    private final JdbcTemplate jdbcTemplate;
    public MessageDAOImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }


    //@Override
    public void saveMessage(String clientID, String message) {
        jdbcTemplate.update(
                "INSERT INTO messages (client_id, text, date_time) VALUES (?, ?, ?);",
                clientID, message, new java.util.Date()
        );
    }

    public List<Message> getUsersMessages (String telegramId){
        try {
            return jdbcTemplate.query("select * from messages where client_id like ? order by date_time;",
                    new Object[]{telegramId}, new MessageMapper()
            );
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

}
