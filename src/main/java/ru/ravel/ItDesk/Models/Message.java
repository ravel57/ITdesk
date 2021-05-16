package ru.ravel.ItDesk.Models;

import com.google.gson.annotations.Expose;
import lombok.*;

import java.util.Date;

@Getter
@Setter
@Builder
@AllArgsConstructor
//@NoArgsConstructor
public class Message {
    @Expose
    long id;
    long clientId;
    @Expose
    String text;
    @Expose
    Date date;
    @Expose
    String messageType;
    @Expose
    long supportId;
    @Expose
    boolean selected;

    public Message() {
    }


}
