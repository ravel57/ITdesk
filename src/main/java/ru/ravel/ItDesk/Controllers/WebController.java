package ru.ravel.ItDesk.Controllers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.ClientTask;
import ru.ravel.ItDesk.Models.Message;
import ru.ravel.ItDesk.Models.Task;
import ru.ravel.ItDesk.Service.Impls.ClientServiceImpl;
import ru.ravel.ItDesk.Service.Impls.MessageServiceImpl;
import ru.ravel.ItDesk.Service.Impls.TaskServiceImpl;

import javax.servlet.http.HttpSession;
import java.util.List;

@Controller
@RequestMapping("/")
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class WebController {

    private final ClientServiceImpl clients;
    private final MessageServiceImpl messages;
    private final TaskServiceImpl tasks;

    @GetMapping("/*")
    public String getRootRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof AnonymousAuthenticationToken)) {
            return "redirect:/dialogs";
        } else
            return "redirect:/login";
    }


    @GetMapping("/dialogs")
    public String getMainRequest(HttpSession httpSession /*, Model model*/) {
        List<ClientTask> clientTasks = this.clients.getActiveClients();
        Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
        httpSession.setAttribute("clients", gson.toJson(clientTasks).replace('\"', '\''));
        httpSession.setAttribute("currentBlock", "Clients");
        return "Main";
    }

    @GetMapping("/dialogs/{id}")
    public String getDialogRequest(HttpSession httpSession, @PathVariable("id") long clientId) {
        Client client = clients.getClient(clientId);
        List<Message> messages = this.messages.getClientsMessages(client);
        List<Task> tasks = this.tasks.getClientTasks(client);
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

    @MessageMapping("/changeClient")
    @SendTo("topic/activity")
    public void getNewTaskFromFrontend(Client client) {
        if (!(client.getLastName() + client.getFirstName()).isEmpty()) {
            clients.changeClient(client);
            clients.sendClientToFront(client);
        }
    }

}