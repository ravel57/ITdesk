package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.time.ZonedDateTime;


@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Message implements Comparable<Message> {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(length = 2048)
	private String text;

	private ZonedDateTime date;

	@ManyToOne(fetch = FetchType.EAGER)
	private User user;

	@Builder.Default
	private boolean isRead = false;

	@Builder.Default
	private boolean isSent = false;

	@Builder.Default
	private boolean isComment = false;

	private Long messengerMessageId;

	@Override
	public int compareTo(@NotNull Message o) {
		return getDate().compareTo(o.getDate());
	}
}
