package ru.ravel.ItDesk.Controllers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import ru.ravel.ItDesk.Service.Impls.TaskServiceImpl;
import ru.ravel.ItDesk.Service.Interfaces.ClientServiceInterface;
import ru.ravel.ItDesk.Service.Interfaces.MessageServiceInterface;

import javax.servlet.http.HttpSession;
import java.util.List;

@Controller
@RequestMapping("/")
public class WebController {

    @Autowired
    ClientServiceInterface clients;
    @Autowired
    MessageServiceInterface messages;
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
    public String getDialogRequest(HttpSession httpSession, @PathVariable("id") long id) {
        Client client = clients.getClient(id);
        List<Message> messages = this.messages.getUsersMessages(client.getId());
        List<Task> tasks = this.tasks.getClientTasks(client);
        for (int i = 0; i < messages.size(); i++)
            messages.get(i).setId(i);
//        List<String> messages = this.messages.getUsersMessages(client.getId()).stream()
//                .map(Message::getText)
//                .collect(Collectors.toList());
//        messages=messages.stream().map(message->message.replaceAll("\n", "<br/>")).collect(Collectors.toList());
        Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
        System.out.println(gson.toJson(messages));
        httpSession.setAttribute("client", client);
        httpSession.setAttribute("messages", messages);
//        httpSession.setAttribute("messages", gson.toJson(messages));
        httpSession.setAttribute("id", id);
        httpSession.setAttribute("tasks", tasks);
        httpSession.setAttribute("currentBlock", "Dialog");
        return "Main";
    }


    @MessageMapping("/messagesa")
    @SendTo("topik/messages")
    public void getMessageFromFrontend(Message message) {
        if (message.getText() != "") {
            messages.saveReplyMessage(message);
            messages.sendMessagesToBot(message);
        }
    }

}
