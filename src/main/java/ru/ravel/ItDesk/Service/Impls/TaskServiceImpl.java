package ru.ravel.ItDesk.Service.Impls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.DAO.Impls.TaskDAOImpl;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Task;

import java.util.List;

@Service
public class TaskServiceImpl {

    @Autowired
    TaskDAOImpl taskDAO;

    public List<Task> getClientTasks(Client client) {
        return taskDAO.getClientTasks(client);
    }

}
