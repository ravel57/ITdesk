package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;


/**
 * Подсказка оператору, опубликованная автоматизацией: внутренний комментарий в чате клиента, его источник
 * и оценка оператора («помогло» / «не помогло»). По оценкам настраиваются пороги источников в правилах.
 * Источник и названия задаёт граф правила; название правила сохраняется на момент публикации, чтобы история
 * не терялась после переименования или удаления правила.
 */
@Entity
@Table(name = "operator_hint")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperatorHint {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "created_at", nullable = false)
	private ZonedDateTime createdAt;

	@Column(name = "client_id", nullable = false)
	private Long clientId;

	/** Сообщение клиента, к которому подсказка. */
	@Column(name = "source_message_id")
	private Long sourceMessageId;

	/** Внутренний комментарий с подсказкой. */
	@Column(name = "note_message_id")
	private Long noteMessageId;

	/** Ключ источника из графа: article, task, reply или свой. */
	@Column(nullable = false, length = 64)
	private String source;

	/** Название источника для статистики, например «Статьи базы знаний». */
	@Column(name = "source_label")
	private String sourceLabel;

	/** Статья, заявка или ответ оператора, из которых сделана подсказка. */
	@Column(name = "source_ref_id")
	private Long sourceRefId;

	@Column(length = 255)
	private String title;

	/** Уверенность Jev при выборе источника. */
	private Double probability;

	@Column(name = "rule_id")
	private Long ruleId;

	/** Название правила на момент публикации. */
	@Column(name = "rule_name")
	private String ruleName;

	/** true — помогло, false — не помогло, null — без оценки. */
	private Boolean helpful;

	@Column(name = "feedback_user_id")
	private Long feedbackUserId;

	@Column(name = "feedback_at")
	private ZonedDateTime feedbackAt;

}
