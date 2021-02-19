package ru.ravel.ItDesk.DAO.Impls;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.DAO.Interfaces.ClientDAOInterface;
import ru.ravel.ItDesk.Mappers.ClientMapper;
import ru.ravel.ItDesk.Models.Client;

import java.util.List;


@Repository
public class ClientDAOImpl implements ClientDAOInterface {

    private final JdbcTemplate jdbcTemplate;

    public ClientDAOImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Client> getAllUser() {
        return jdbcTemplate.query("select * from client", new ClientMapper());
    }

    public Client getUser(long id) {
        Client user;
        try {
            user = jdbcTemplate.queryForObject("select * from client", new ClientMapper());
            return user;
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
