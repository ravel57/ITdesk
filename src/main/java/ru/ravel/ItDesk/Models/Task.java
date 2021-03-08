package ru.ravel.ItDesk.Models;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Task {
    long id;
    long clientId;
    String name;
//    String status;
//    String priority;
}
