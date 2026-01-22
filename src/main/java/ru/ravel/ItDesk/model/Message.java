package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import org.jetbrains.annotations.NotNull;

import java.time.ZonedDateTime;
import java.util.UUID;


@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message implements Comparable<Message> {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(length = 32768)
	private String text;

	@Builder.Default
	private ZonedDateTime date = ZonedDateTime.now();

	@ManyToOne(fetch = FetchType.EAGER)
	private User user;

	@Builder.Default
	private boolean isRead = false;

	@Builder.Default
	private boolean isSent = false;

	@Builder.Default
	private boolean isComment = false;

	private Integer messengerMessageId;

	@Builder.Default
	private Boolean deleted = false;

	private String fileName;

	private String fileType;

	private String fileUuid;

	private Integer fileHeight;

	private Integer fileWidth;

	private Long replyMessageId;

	private UUID replyUuid;

	private String replyFileType;

	@Transient
	private String replyMessageText;

	private Integer replyMessageMessengerId;

	@Transient
	private Long linkedTaskId;

	@Override
	public int compareTo(@NotNull Message o) {
		if (getDate() == null) return -1;
		if (o.getDate() == null) return 1;
		return getDate().compareTo(o.getDate());
	}
}