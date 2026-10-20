package ru.ravel.ItDesk.model.jev;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;


/**
 * Один вопрос в запросе Jev: ответ, ошибка или пропуск; условия графа принимают решение отдельно.
 * Сохраняются вопрос, выбранный вариант и метаданные; состояние запроса целиком не копируется.
 */
@Entity
@Table(name = "jev_decision")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class JevDecision {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "created_at", nullable = false)
	private ZonedDateTime createdAt;

	@Column(name = "rule_id")
	private Long ruleId;

	@Column(name = "rule_name")
	private String ruleName;

	/** Действие автоматизации, например createTaskIfNeeded */
	@Column(length = 64)
	private String action;

	/** Объект решения: «task=12», «client=3, message=40» */
	private String context;

	/** Вопрос к Jev: kind, open_match, priority, needs_reply… */
	@Column(nullable = false, length = 64)
	private String question;

	/** Выбранный вариант; для да/нет-вопросов — «да»/«нет» по порогу */
	private String choice;

	/** Уверенность (choice) или вероятность «да» (noul) */
	@Column
	private Double confidence;

	private Double threshold;

    /** Request correlation and graph location; optional for historical decisions. */
    private String requestId;
    private String source;
    private String responseStatus;
    private Long eventId;
    private String executionId;
    private String nodeId;
    private String nodeLabel;
    @Column(length = 1024)
    private String questionTitle;
    private String answerType;
    @Column(length = 1000)
    private String errorMessage;
    private Long traceId;

	/** ANSWERED/FAILED/SKIPPED/PENDING/INVALID; прежние методы также сохраняют свой итог проверки. */
	@Column(nullable = false, length = 32)
	private String outcome;
}
