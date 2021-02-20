package ru.ravel.ItDesk.Controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Service.Interfaces.ClientServiceInterface;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/")
public class WebController {

    @Autowired
    ClientServiceInterface clientServiceInterface;

    @GetMapping()
    public String getRootRequest() {
        return "redirect:/dialogs";
    }

    @GetMapping("/dialogs")
    public String getMainRequest(HttpSession httpSession /*, Model model*/) {
        List<String> names = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Client client : clientServiceInterface.getActiveClients()) {
            names.add(client.getFirstName() + " " + client.getLastName());
            ids.add(client.getId());
        }
        httpSession.setAttribute("dialogs", names);
        httpSession.setAttribute("currentBlock", "Tasks");
        return "Main";
    }

    @GetMapping("/dialogs/{id}")
    public String getDialogRequest(HttpSession httpSession/*, @PathVariable("id") int id*/) {
        List<String> messages = new ArrayList<>();
        messages.add("123");
        messages.add("234");
        messages.add("345");
        httpSession.setAttribute("messages", messages);
        httpSession.setAttribute("currentBlock", "Dialog");
        return "Main";
    }
}
