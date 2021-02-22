package ru.ravel.ItDesk.DAO.Impls;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.DAO.Interfaces.ClientDAOInterface;
import ru.ravel.ItDesk.Mappers.ClientMapper;
import ru.ravel.ItDesk.Models.Client;

import java.util.List;
import java.util.Optional;


@Repository
public class ClientDAOImpl implements ClientDAOInterface {

    private final JdbcTemplate jdbcTemplate;

    public ClientDAOImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Client> getAllUser() {
        return jdbcTemplate.query("select * from clients ", new ClientMapper());
    }

    public Client getUser(long id) {
        Client user;
        try {
            user = jdbcTemplate.queryForObject("select clients.id, FirstName, LastName," +
                            "organizations.name as organization,\n" +
                            "telegram_id,whatsapp_id,cabinet_number,phone_number,email\n" +
                            "from clients\n" +
                            "join organizations on organizations.id = clients.organization_id",
                    new ClientMapper());
            return user;
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public List<Client> getActiveClients() {
        return jdbcTemplate.query("select clients.id, FirstName, LastName,organizations.name as organization, " +
                        "telegram_id,whatsapp_id,cabinet_number,phone_number,email " +
                        "from clients " +
                        "join organizations on organizations.id = clients.organization_id;",
                new ClientMapper());
    }

    @Override
    public Client getClientById(String telegramId) {
        try {
            return jdbcTemplate.queryForObject("select clients.id, FirstName, LastName, organizations.name as organization,\n" +
                            "telegram_id,whatsapp_id,cabinet_number,phone_number,email\n" +
                            "from clients\n" +
                            "join organizations on organizations.id = clients.organization_id\n" +
                            "where clients.id like ?;",
                    new Object[]{telegramId}, new ClientMapper()
            );
        } catch (EmptyResultDataAccessException e) {
//            throw e;
            return new Client();
        }
    }

    @Override
    public boolean registered(String telegramId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("select clients.id, FirstName, LastName," +
                            "organizations.name as organization,\n" +
                            "telegram_id,whatsapp_id,cabinet_number,phone_number,email\n" +
                            "from clients\n" +
                            "join organizations on organizations.id = clients.organization_id\n " +
                            "where telegram_id like ?;",
                    new Object[]{telegramId}, new ClientMapper()
            )).isPresent();
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    @Override
    public void addUser(Client client) {
        jdbcTemplate.update(
                "INSERT INTO clients (FirstName, Lastname, telegram_id) VALUES (?, ?, ?)",
                client.getFirstName(), client.getLastName(), client.getTelegramId()
        );
    }
}
