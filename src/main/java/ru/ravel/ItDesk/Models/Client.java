package ru.ravel.ItDesk.Models;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Client {
    private long id;
    private String firstName;
    private String lastName;
    private String organization;
    private String telegramId;
    private long whatsappId;
    private String cabinetNumber;
    private String phoneNumber;
    private String email;

}
