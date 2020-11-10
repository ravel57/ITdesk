package ru.ravel.ItDesk.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/")
public class MainController {
    @GetMapping()
    public String getRootRequest() {
        return "redirect:/dialogs";
    }

    @GetMapping("/dialogs")
    public String getMainRequest(HttpSession httpSession /*, Model model*/) {
        List<String> dialogs = new ArrayList<>();
        dialogs.add("1");
        dialogs.add("2");
        dialogs.add("3");
        dialogs.add("4");
        dialogs.add("5");
        httpSession.setAttribute("dialogs", dialogs);
        return "Main";
    }

    @GetMapping("/dialogs/{id}")
    public String getDialogRequest (@PathVariable("id") int id){
        return "Dialog";
    }
}
