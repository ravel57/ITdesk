package ru.ravel.ItDesk.Models;

import lombok.*;

import java.util.Date;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Client {
    private long id;
    private String firstName;
    private String lastName;
    private String userName;
    private String organization;
    private long telegramId;
    private long whatsappId;
    private String cabinetNumber;
    private String phoneNumber;
    private String email;
}
