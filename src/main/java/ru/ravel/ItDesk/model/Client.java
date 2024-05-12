package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

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

	@Column(length = 2048)
	private String moreInfo;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "organization_id")
	private Organization organization;

	private Long telegramId;

	private Long whatsappId;

	private String phoneNumber;

	private String email;

	@OneToMany(fetch = FetchType.EAGER)
	@JoinColumn(name = "client_id")
	@Builder.Default
	private List<Task> tasks = new ArrayList<>();

	@OneToMany(fetch = FetchType.EAGER)
	@JoinColumn(name = "client_id")
	@Builder.Default
	private List<Message> messages = new ArrayList<>();

	@JsonIgnore
	@ManyToOne(fetch = FetchType.EAGER)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private TgBot tgBot;
}
