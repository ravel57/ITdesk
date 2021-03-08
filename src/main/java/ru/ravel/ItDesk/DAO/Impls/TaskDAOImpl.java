package ru.ravel.ItDesk.DAO.Impls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.Mappers.ClientTaskMapper;
import ru.ravel.ItDesk.Mappers.TaskMapper;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Task;

import java.util.List;

@Repository
public class TaskDAOImpl {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Task> getClientTasks(Client client) {
        try {
            return jdbcTemplate.query(
                    "select id, client_id, text from tasks where client_id like ?;",
                    new Object[]{client.getId()}, new TaskMapper()
            );
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

}
