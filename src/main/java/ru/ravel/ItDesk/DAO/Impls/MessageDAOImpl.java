package ru.ravel.ItDesk.DAO.Impls;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.DAO.Interfaces.MessageDAOInterface;
import ru.ravel.ItDesk.Service.Interfaces.MessageServiceInterface;

@Repository
public class MessageDAOImpl implements MessageDAOInterface {

    private final JdbcTemplate jdbcTemplate;
    public MessageDAOImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }


    //@Override
    public void saveMessage(String clientID, String message) {
        jdbcTemplate.update(
                "INSERT INTO messages (client_id, text, date_time) VALUES (?, ?, ?)",
                clientID, message, new java.util.Date()
        );
    }

}
