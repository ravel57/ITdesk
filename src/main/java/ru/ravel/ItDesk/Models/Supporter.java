package ru.ravel.ItDesk.Models;


import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Supporter {
    long id;
    String name;
//    long telegramId;
    String login;
}
