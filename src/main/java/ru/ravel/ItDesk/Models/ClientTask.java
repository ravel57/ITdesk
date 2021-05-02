package ru.ravel.ItDesk.Models;

import com.google.gson.annotations.Expose;
import lombok.*;

import java.util.Date;
import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClientTask {
    @Expose
    private long id;
    @Expose
    private String firstName;
    @Expose
    private String lastName;
    @Expose
    private String organization;
    private String telegramId;
    @Expose
    private Date lastMessageDateTime;
    @Expose
    private List<Task> tasks;
}
