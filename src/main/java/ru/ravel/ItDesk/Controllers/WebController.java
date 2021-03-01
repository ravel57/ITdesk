package ru.ravel.ItDesk.Controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Message;
import ru.ravel.ItDesk.Models.ReplayMessage;
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
    public String getDialogRequest(HttpSession httpSession, @PathVariable("id") long id) {
        Client client = clients.getClient(id);
        List<Message> messages = this.messages.getUsersMessages(client.getId());
        for (int i = 0; i < messages.size(); i++)
            messages.get(i).setId(i);
//        List<String> messages = this.messages.getUsersMessages(client.getId()).stream()
//                .map(Message::getText)
//                .collect(Collectors.toList());
//        messages=messages.stream().map(message->message.replaceAll("\n", "<br/>")).collect(Collectors.toList());
        httpSession.setAttribute("client", client);
        httpSession.setAttribute("messages", messages);
        httpSession.setAttribute("id", id);
        httpSession.setAttribute("currentBlock", "Dialog");
        return "Main";
    }


    @MessageMapping("/messagesa")
    @SendTo("topik/messages")
    public void sendMessagesToBot(ReplayMessage replyeMessage) {
        messages.sendMessagesToBot(replyeMessage);
    }

}
