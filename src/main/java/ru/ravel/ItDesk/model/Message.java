package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.time.ZonedDateTime;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class Message implements Comparable<Message> {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	String text;

	ZonedDateTime date;

	@ManyToOne(targetEntity = User.class, fetch = FetchType.EAGER)
	User user;

	@Builder.Default
	boolean isRead = false;

	@Builder.Default
	boolean isSent = false;

	@Builder.Default
	boolean isComment = false;

	@Override
	public int compareTo(@NotNull Message o) {
		return getDate().compareTo(o.getDate());
	}
}
