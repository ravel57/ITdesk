package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskService {
//    @Autowired
//    private SimpMessagingTemplate template;


//	public List<Task> getClientTasks(long clientId) {
//		List<Task> tasks = taskDAO.getClientTasks(clientId);
//		for (long i = 0; i < tasks.size(); i++)
//			tasks.get((int) i).setId(i);
//		return tasks;
//	}
//
//	public List<Task> getClientActualTasksById(long clientId) {
//		List<Task> tasks = taskDAO.getClientActualTasks(clientId);
//		for (long i = 0; i < tasks.size(); i++)
//			tasks.get((int) i).setId(i);
//		return tasks;
//	}
//
//	public void saveTask(Task task) {
//		List<Task> ct = taskDAO.getClientTasks(task.getClientId());
//		if (ct.size() <= task.getId())
//			taskDAO.saveNewTask(task);
//		else
//			taskDAO.changeTask(task);
//	}

//    public void sendTaskToFront(Task task) {
//        this.template.convertAndSend("/topic/activity", task);
//    }

}
