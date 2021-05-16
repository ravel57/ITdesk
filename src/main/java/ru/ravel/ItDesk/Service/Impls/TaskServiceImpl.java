package ru.ravel.ItDesk.Service.Impls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.DAO.Impls.ClientDAOImpl;
import ru.ravel.ItDesk.DAO.Impls.TaskDAOImpl;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Task;

import java.util.List;

@Service
public class TaskServiceImpl {

    @Autowired
    private TaskDAOImpl taskDAO;
    @Autowired
    private SimpMessagingTemplate template;


    public List<Task> getClientTasks(long clientId) {
        List<Task> tasks = taskDAO.getClientTasks(clientId);
        for (int i = 0; i < tasks.size(); i++)
            tasks.get(i).setId(i);
        return tasks;
    }

    public List<Task> getClientActualTasksById(long clientId) {
        List<Task> tasks = taskDAO.getClientActualTasks(clientId);
        for (int i = 0; i < tasks.size(); i++)
            tasks.get(i).setId(i);
        return tasks;
    }

    public void saveTask(Task task) {
        List<Task> ct = taskDAO.getClientTasks(task.getClientId());
        if (ct.size() <= task.getId())
            taskDAO.saveNewTask(task);
        else
            taskDAO.changeTask(task);
    }

    public void sendTaskToFront(Task task) {
        this.template.convertAndSend("/topic/activity", task);
    }

}
