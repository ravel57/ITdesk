package ru.ravel.ItDesk.Controllers;

import com.google.gson.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Message;
import ru.ravel.ItDesk.Models.Task;
import ru.ravel.ItDesk.Service.Impls.MessageServiceImpl;
import ru.ravel.ItDesk.Service.Impls.TaskServiceImpl;
import ru.ravel.ItDesk.Service.Interfaces.ClientServiceInterface;

import javax.servlet.http.HttpSession;
import java.util.List;

@Controller
@RequestMapping("/")
public class WebController {

    @Autowired
    ClientServiceInterface clients;
    @Autowired
    MessageServiceImpl messages;
    @Autowired
    TelegramBotController bot;
    @Autowired
    TaskServiceImpl tasks;

    @GetMapping()
    public String getRootRequest() {
        return "redirect:/dialogs";
    }

    @GetMapping("/dialogs")
    public String getMainRequest(HttpSession httpSession /*, Model model*/) {
        httpSession.setAttribute("clients", this.clients.getActiveClients());
        httpSession.setAttribute("currentBlock", "Clients");
        return "Main";
    }

    @GetMapping("/dialogs/{id}")
    public String getDialogRequest(HttpSession httpSession, @PathVariable("id") long clientId) {
        Client client = clients.getClient(clientId);
        List<Message> messages = this.messages.getClientsMessages(client);
        List<Task> tasks = this.tasks.getClientTasks(client);
        for (int i = 0; i < messages.size(); i++)
            messages.get(i).setId(i);
        for (int i = 0; i < tasks.size(); i++)
            tasks.get(i).setId(i);
//        messages=messages.stream().map(message->message.replaceAll("\n", "<br/>")).collect(Collectors.toList());
        Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
        httpSession.setAttribute("client", gson.toJson(client).replace('\"', '\''));
        httpSession.setAttribute("messages", gson.toJson(messages).replace('\"', '\''));
        httpSession.setAttribute("tasks", gson.toJson(tasks).replace('\"', '\''));
        httpSession.setAttribute("clientId", clientId);
        httpSession.setAttribute("currentBlock", "Dialog");
        return "Main";
    }


    @MessageMapping("/changeMessage")
    @SendTo("topic/activity")
    public void getSupportMessageFromFrontend(Message message) {
        if (!message.getText().isEmpty()) {
            messages.saveSupportMessage(message);
            messages.sendMessagesToBot(message);
            messages.sendMessagesToFront(message);
        }
    }

    @MessageMapping("/changeTask")
    @SendTo("topic/activity")
    public void getNewTaskFromFrontend(Task task) {
        if (!task.getText().isEmpty()) {
            tasks.saveTask(task);
            tasks.sendTaskToFront(task);
        }
    }

}