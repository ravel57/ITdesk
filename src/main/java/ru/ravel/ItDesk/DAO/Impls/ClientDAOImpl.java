package ru.ravel.ItDesk.DAO.Impls;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.DAO.Interfaces.ClientDAOInterface;
import ru.ravel.ItDesk.Mappers.ClientMapper;
import ru.ravel.ItDesk.Mappers.ClientTaskMapper;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.ClientTask;

import java.util.List;


@Repository
public class ClientDAOImpl /*implements ClientDAOInterface */{

    private final JdbcTemplate jdbcTemplate;

    public ClientDAOImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Client> getAllClients() {
        return jdbcTemplate.query("select * from messages\n" +
                "group by client_id\n" +
                "order by date_time; ", new ClientMapper());
    }

    public Client getClientById(long id) {
        Client user;
        try {
            user = jdbcTemplate.queryForObject(
                    "select clients.id, FirstName, LastName, " +
                            "organizations.name as organization, " +
                            "telegram_id,whatsapp_id,cabinet_number,phone_number,email " +
                            "from clients " +
                            "left join organizations on organizations.id = clients.organization_id " +
                            "where clients.id = ?;",
                    new Object[]{id}, new ClientMapper());
            return user;
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

//    @Override
    public List<ClientTask> getActiveClients(Object supportId) {
        return jdbcTemplate.query(
                "select clients.id, FirstName, LastName, organizations.name as organization,\n" +
                        "message_type.message_type as last_message_type, date_time as last_message, telegram_id, whatsapp_id,\n" +
                        "cabinet_number, email, readed\n" +
                        "from  messages\n" +
                        "left join clients on messages.client_id = clients.id\n" +
                        "left join organizations on organizations.id = clients.organization_id\n" +
                        "INNER JOIN (\n" +
                        "    SELECT client_id, MAX(date_time) AS date_time\n" +
                        "    FROM messages GROUP BY client_id\n" +
                        ") AS max USING (client_id, date_time)\n" +
                        "left join message_type on message_type.id = messages.message_type\n" +
                        "join support_clients_read scr on clients.id = scr.clients_id \n" +
                        "where clients.id is not null and scr.support_id = ? " +
                        "group by client_id\n" +
                        "order by last_message desc;",
                new Object[]{supportId},
                new ClientTaskMapper());
    }

//    @Override
    public Client getClientByTelegramId(long telegramId) {
        try {
            return jdbcTemplate.queryForObject(
                    "select clients.id, FirstName, LastName, organizations.name as organization,\n" +
                            "telegram_id,whatsapp_id,cabinet_number,phone_number,email\n" +
                            "from clients\n" +
                            "left join organizations on organizations.id = clients.organization_id\n" +
                            "where clients.telegram_id = ?;",
                    new Object[]{telegramId}, new ClientMapper()
            );
        } catch (EmptyResultDataAccessException e) {
//            throw e;
            return new Client();
        }
    }

//    @Override
    public String getTelegramIdByClientId(long clientId) {
        try {
            return jdbcTemplate.queryForObject(
                    "select telegram_id from clients where id = ?;",
                    new Object[]{clientId}, String.class
            );
        } catch (EmptyResultDataAccessException e) {
            throw e;
        }
    }

//    @Override
//    public boolean registered(String telegramId) {
//        try {
//            return Optional.ofNullable(jdbcTemplate.queryForObject("select clients.id, FirstName, LastName," +
//                            "organizations.name as organization,telegram_id," +
//                            //"whatsapp_id,cabinet_number,phone_number,email\n" +
//                            "from clients\n" +
//                            "left join organizations on organizations.id = clients.organization_id\n " +
//                            "where telegram_id like ?;",
//                    new Object[]{telegramId}, new ClientMapper()
//            )).isPresent();
//        } catch (EmptyResultDataAccessException e) {
//            return false;
//        }
//    }

//    @Override
    public void addClient(Client client) {
        jdbcTemplate.update(
                "INSERT INTO clients (FirstName, Lastname, telegram_id, username) VALUES (?, ?, ?, ?)",
                client.getFirstName(), client.getLastName(), client.getTelegramId(), client.getUserName()
        );
    }


    public void changeClient(Client client) {
        jdbcTemplate.update(
                "UPDATE it_desk.clients t " +
                        "SET t.FirstName = ?, " +
                        "    t.LastName  = ? " +
//                        "t.organization_id = ? " +
                        "WHERE t.id = ?;",
                client.getFirstName(), client.getLastName(), client.getId()
        );
    }
}
