package ru.ravel.ItDesk.Models;

import com.google.gson.annotations.Expose;
import lombok.*;

import java.util.Date;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Client {
    @Expose
    private long id;
    @Expose
    private String firstName;
    @Expose
    private String lastName;
    @Expose
    private String userName;
    @Expose
    private String organization;
    private long telegramId;
    private long whatsappId;
    private String cabinetNumber;
    private String phoneNumber;
    private String email;
}
