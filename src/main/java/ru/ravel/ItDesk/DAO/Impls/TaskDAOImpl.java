package ru.ravel.ItDesk.DAO.Impls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.Mappers.TaskMapper;
import ru.ravel.ItDesk.Models.Task;

import java.util.List;

@Repository
public class TaskDAOImpl {

    @Autowired
    private JdbcTemplate jdbcTemplate;

//    public List<Task> getClientTasks(Client client) {
//        try {
//            return jdbcTemplate.query(
//                    "select id, client_id, text, actual " +
//                            "from tasks " +
//                            "where client_id like ?;",
//                    new Object[]{client.getId()}, new TaskMapper()
//            );
//        } catch (Exception e) {
//            e.printStackTrace();
//            throw e;
//        }
//    }

    public List<Task> getClientTasks(long clientId) {
        try {
            return jdbcTemplate.query(
                    "select id, client_id, text, actual, message_id " +
                            "from tasks " +
                            "where client_id like ?;",
                    new Object[]{clientId}, new TaskMapper()
            );
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public List<Task> getClientActualTasks(long clientId) {
        try {
            return jdbcTemplate.query(
                    "select id, client_id, text, actual, message_id " +
                            "from tasks " +
                            "where client_id like ? and actual;",
                    new Object[]{clientId}, new TaskMapper()
            );
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public void saveNewTask(Task task) {
        try {
            jdbcTemplate.update(
                    "insert into tasks (client_id, text, actual) " +
                            "values (?, ?, ?);",
                    task.getClientId(), task.getText(), task.isActual()
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void changeTask(Task task) {
        try {
            long localTaskId = jdbcTemplate.queryForObject(
                    "select id from tasks where client_id = ? limit 1 OFFSET ?",
                    new Object[]{task.getClientId(), task.getId()},
                    long.class
            );
            jdbcTemplate.update(
                    "UPDATE tasks SET actual = ?, text = ?, message_id = ? " +
                            "WHERE (id = ?);",
                    task.isActual(),
                    task.getText(),
                    task.getMessageId(),
                    localTaskId
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
