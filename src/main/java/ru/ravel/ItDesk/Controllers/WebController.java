package ru.ravel.ItDesk.Controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Message;
import ru.ravel.ItDesk.Service.Interfaces.ClientServiceInterface;
import ru.ravel.ItDesk.Service.Interfaces.MessageServiceInterface;

import javax.servlet.http.HttpSession;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/")
public class WebController {

    @Autowired
    ClientServiceInterface clients;
    @Autowired
    MessageServiceInterface messages;

    @GetMapping()
    public String getRootRequest() {
        return "redirect:/dialogs";
    }

    @GetMapping("/dialogs")
    public String getMainRequest(HttpSession httpSession /*, Model model*/) {
        httpSession.setAttribute("clients", clients.getActiveClients());
        httpSession.setAttribute("currentBlock", "Tasks");
        return "Main";
    }

    @GetMapping("/dialogs/{id}")
    public String getDialogRequest(HttpSession httpSession, @PathVariable("id") String id) {
        Client client = clients.getClientById(id);
        List<String> messages = this.messages.getUsersMessages(client.getTelegramId()).stream()
                .map(Message::getText)
                .collect(Collectors.toList());
        httpSession.setAttribute("client", client);
        messages=messages.stream().map(message->message.replaceAll("\n", "<br/>")).collect(Collectors.toList());
        httpSession.setAttribute("messages", messages);
        httpSession.setAttribute("currentBlock", "Dialog");
        return "Main";
    }
}
