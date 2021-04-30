package ru.ravel.ItDesk.Models;

import lombok.*;

import java.util.Date;
import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClientTask {
    private long id;
    private String firstName;
    private String lastName;
    private String organization;
    private String telegramId;
    private Date lastMessage;
    private List<Task> tasks;
}
