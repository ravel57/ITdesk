package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
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

	private String firstname;

	private String lastname;

	private String moreInfo;

	@ManyToOne(fetch = FetchType.EAGER)
	private Organization organization;

	private Long telegramId;

	private Long whatsappId;

	private String phoneNumber;

	private String email;

	@OneToMany(fetch = FetchType.EAGER)
	@Builder.Default
	private List<Task> tasks = new ArrayList<>();

	@OneToMany(fetch = FetchType.EAGER)
	@Builder.Default
	private List<Message> messages = new ArrayList<>();
}
