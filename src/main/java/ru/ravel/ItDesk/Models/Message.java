package ru.ravel.ItDesk.Models;

import lombok.*;

import java.util.Date;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Message {
    long id;
    long clientId;
    String text;
    Date date;

}
