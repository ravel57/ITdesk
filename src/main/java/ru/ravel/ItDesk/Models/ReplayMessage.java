package ru.ravel.ItDesk.Models;

import lombok.*;

import java.util.Date;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReplayMessage {
    long id;
    long clientId;
    long supportId;
    String text;
    Date date;
}