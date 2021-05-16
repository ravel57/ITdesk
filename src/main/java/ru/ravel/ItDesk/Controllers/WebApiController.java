package ru.ravel.ItDesk.Controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Message;
import ru.ravel.ItDesk.Models.Task;
import ru.ravel.ItDesk.Service.Impls.ClientServiceImpl;
import ru.ravel.ItDesk.Service.Impls.MessageServiceImpl;
import ru.ravel.ItDesk.Service.Impls.TaskServiceImpl;

import java.util.List;

@RestController
@RequestMapping("/api/v1/")
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class WebApiController {

    private final ClientServiceImpl clients;
    private final MessageServiceImpl messages;
    private final TaskServiceImpl tasks;


    @GetMapping("/clients")
    public ResponseEntity<Object> getActualClientRequest() {
        return ResponseEntity.status(HttpStatus.OK).body(this.clients.getActiveClients());
    }

    @GetMapping("/messages/{id}")
    public ResponseEntity<Object> getMessagesRequest(@PathVariable("id") long clientId) {
        messages.markChatRead(SecurityContextHolder.getContext().getAuthentication().getPrincipal(), clientId);
        SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        List<Message> messages = this.messages.getClientsMessagesById(clientId);
        return ResponseEntity.status(HttpStatus.OK).body(messages);
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<Object> getTasksRequest(@PathVariable("id") long clientId) {
        List<Task> tasks = this.tasks.getClientTasks(clientId);
        return ResponseEntity.status(HttpStatus.OK).body(tasks);
    }

    @GetMapping("/client/{id}")
    public ResponseEntity<Object> getClientRequest(@PathVariable("id") long clientId) {
        Client client = this.clients.getClient(clientId);
        return ResponseEntity.status(HttpStatus.OK).body(client);
    }
}
