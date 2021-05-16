package ru.ravel.ItDesk.Models;

import com.google.gson.annotations.Expose;
import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Task {
    @Expose
    long id;
    @Expose
    long clientId;
    @Expose
    String text;
    @Expose
    boolean actual;
    @Expose
    Object messageId;
//    String status;
//    String priority;
}
