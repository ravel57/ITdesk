package ru.ravel.ItDesk.models;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class Client {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String firstName;

	private String lastName;

	private String userName;

	private String organization;

	private Long telegramId;

	private Long whatsappId;

//	private String cabinetNumber;

	private String phoneNumber;

	private String email;

	@OneToMany(targetEntity = Task.class)
	private List<Task> tasks;

	@OneToMany(targetEntity = Message.class)
	private List<Message> messages;
}
