package ru.ravel.ItDesk.model.jev;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;


/**
 * Одно решение Jev: ответ на один вопрос в рамках действия автоматизации.
 * Тексты обращений не сохраняются — только выбор, уверенность и итог.
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
	@Column(nullable = false)
	private Double confidence;

	private Double threshold;

	/** ACCEPTED, REJECTED (ниже порога), NONE («ни один вариант»), UNDECIDED (между порогами) */
	@Column(nullable = false, length = 32)
	private String outcome;
}
