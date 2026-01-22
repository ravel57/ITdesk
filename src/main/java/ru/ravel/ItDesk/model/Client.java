package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Client {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String firstname;

	private String lastname;

	@Column(length = 2048)
	private String moreInfo;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name = "organization_id")
	private Organization organization;

	private Long telegramId;

	private String whatsappRecipient;

	private String phoneNumber;

	private String email;

	@OneToMany(fetch = FetchType.EAGER, orphanRemoval = true)
	@JoinColumn(name = "client_id")
	@Builder.Default
	private List<Task> tasks = new ArrayList<>();

	@OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "client_id")
	@Builder.Default
	@JsonIgnore
	private List<Message> messages = new ArrayList<>();

	@JsonIgnore
	@ManyToOne(fetch = FetchType.EAGER)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@JoinColumn(name = "tg_bot_id")
	private TgBot tgBot;

	@JsonIgnore
	@ManyToOne(fetch = FetchType.EAGER)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@JoinColumn(name = "email_account_id")
	private EmailAccount emailAccountSender;

	@JsonIgnore
	@ManyToOne(fetch = FetchType.EAGER)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@JoinColumn(name = "whatsapp_account_id")
	private WhatsappAccount whatsappAccount;

	@Transient
	private String sourceChannel;

	private MessageFrom messageFrom;

	@Transient
	private Message lastMessage;

	@Transient
	private Long unreadMessagesCount;

	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	// Long = User.id; Boolean = isPinged
	private Map<Long, Boolean> unreadPingMessages = new HashMap<>();

	@Transient
	@Builder.Default
	private Set<User> typingUsers = new ConcurrentSkipListSet<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	private Map<Long, String> typingMessageText = new HashMap<>();

	@Transient
	@Builder.Default
	private Set<User> watchingUsers = new ConcurrentSkipListSet<>();
}
