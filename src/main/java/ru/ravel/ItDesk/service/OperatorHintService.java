package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.OperatorHintDto;
import ru.ravel.ItDesk.dto.OperatorHintStatsDto;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.AutomationRuleStatus;
import ru.ravel.ItDesk.repository.AutomationTriggerRepository;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.OperatorHintRepository;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;


/**
 * Подсказки оператору: публикация внутренним комментарием с записью источника и оценка операторами.
 * Наблюдатель от организации комментарии не видит, поэтому и подсказки ему не отдаются.
 * Какие правила публикуют подсказки, определяется по графу (шаг hint.publish), а не по названию правила.
 */
@Service
@RequiredArgsConstructor
public class OperatorHintService {

	public static final String PUBLISH_OPERATION = "hint.publish";
	private static final Pattern SOURCE_KEY = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
	/** Названия для графов, где название источника не задано. */
	private static final Map<String, String> DEFAULT_SOURCE_LABELS = Map.of(
			"article", "Статьи базы знаний",
			"task", "Закрытые заявки",
			"reply", "История чатов");

	private final OperatorHintRepository hintRepository;
	private final ClientRepository clientRepository;
	private final AutomationTriggerRepository triggerRepository;
	private final ClientService clientService;
	private final UserService userService;
	private final ObjectMapper mapper;


	/** Внутренний комментарий с подсказкой и запись о нём; комментарий помечается id подсказки. */
	@Transactional
	public OperatorHint publish(Long clientId, Long sourceMessageId, String source, String sourceLabel, Long sourceRefId,
	                            String title, Double probability, AutomationTrigger rule, String text) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("Текст подсказки пустой");
		}
		String key = Objects.toString(source, "").strip().toLowerCase(Locale.ROOT);
		if (!SOURCE_KEY.matcher(key).matches()) {
			throw new IllegalArgumentException("Источник подсказки — ключ латиницей, например article: «" + source + "»");
		}
		String label = sourceLabel == null || sourceLabel.isBlank() ? DEFAULT_SOURCE_LABELS.getOrDefault(key, key) : sourceLabel.strip();
		OperatorHint hint = hintRepository.save(OperatorHint.builder()
				.createdAt(ZonedDateTime.now())
				.clientId(clientId)
				.sourceMessageId(sourceMessageId)
				.source(key)
				.sourceLabel(limit(label))
				.sourceRefId(sourceRefId)
				.title(title == null ? null : limit(title))
				.probability(probability)
				.ruleId(rule == null ? null : rule.getId())
				.ruleName(rule == null ? null : limit(rule.getName()))
				.build());
		Message note = clientService.sendMessageForSystem(clientId, Message.builder()
				.text(text.strip())
				.isSent(true)
				.isComment(true)
				.isRead(true)
				.date(ZonedDateTime.now())
				.operatorHintId(hint.getId())
				.build());
		hint.setNoteMessageId(note.getId());
		return hintRepository.save(hint);
	}


	@Transactional(readOnly = true)
	public List<OperatorHintDto> hintsOfClient(Long clientId) {
		User user = userService.getCurrentUser();
		if (userService.isObserver(user)) {
			return List.of();
		}
		userService.assertCurrentUserCanAccessClient(clientRepository.findById(clientId).orElseThrow());
		return hintRepository.findAllByClientIdOrderByIdAsc(clientId).stream().map(OperatorHintDto::of).toList();
	}


	/** helpful: true — помогло, false — не помогло, null — снять оценку. */
	@Transactional
	public OperatorHintDto rate(Long hintId, Boolean helpful) {
		OperatorHint hint = hintRepository.findById(hintId).orElseThrow();
		userService.assertCurrentUserCanAccessClient(clientRepository.findById(hint.getClientId()).orElseThrow());
		User user = userService.getCurrentUser();
		hint.setHelpful(helpful);
		hint.setFeedbackUserId(helpful == null || user == null ? null : user.getId());
		hint.setFeedbackAt(helpful == null ? null : ZonedDateTime.now());
		return OperatorHintDto.of(hintRepository.save(hint));
	}


	/**
	 * Статистика за последние days дней по правилам и источникам; ruleId — только одно правило.
	 * Название правила — текущее, для удалённого — сохранённое при публикации.
	 */
	@Transactional(readOnly = true)
	public OperatorHintStatsDto stats(int days, Long ruleId) {
		Map<Long, AutomationTrigger> triggers = new HashMap<>();
		triggerRepository.findAll().forEach(trigger -> triggers.put(trigger.getId(), trigger));
		Map<Long, List<OperatorHintStatsDto.Source>> sourcesByRule = new LinkedHashMap<>();
		Map<Long, String> savedNames = new HashMap<>();
		for (Object[] row : hintRepository.statsSince(ZonedDateTime.now().minusDays(Math.max(1, days)), ruleId)) {
			Long rule = row[0] == null ? null : ((Number) row[0]).longValue();
			savedNames.putIfAbsent(rule, (String) row[1]);
			sourcesByRule.computeIfAbsent(rule, key -> new ArrayList<>()).add(new OperatorHintStatsDto.Source(
					(String) row[2], (String) row[3], number(row[4]), number(row[5]), number(row[6]),
					row[7] == null ? null : ((Number) row[7]).doubleValue()));
		}
		List<OperatorHintStatsDto.Rule> rules = new ArrayList<>();
		sourcesByRule.forEach((rule, sources) -> {
			AutomationTrigger trigger = rule == null ? null : triggers.get(rule);
			rules.add(new OperatorHintStatsDto.Rule(rule, trigger != null ? trigger.getName() : savedNames.get(rule),
					trigger != null, trigger != null && trigger.getAutomationRuleStatus() == AutomationRuleStatus.ENABLED, sources));
		});
		List<OperatorHintStatsDto.RuleRef> publishing = triggers.values().stream()
				.filter(trigger -> ruleId == null || ruleId.equals(trigger.getId()))
				.filter(this::publishesHints)
				.sorted(Comparator.comparing(AutomationTrigger::getId))
				.map(trigger -> new OperatorHintStatsDto.RuleRef(trigger.getId(), trigger.getName(),
						trigger.getAutomationRuleStatus() == AutomationRuleStatus.ENABLED))
				.toList();
		return new OperatorHintStatsDto(rules, publishing);
	}


	/** В графе правила есть шаг «Опубликовать подсказку оператору». */
	boolean publishesHints(AutomationTrigger trigger) {
		String definition = trigger.getWorkflowDefinition();
		if (definition == null || !definition.contains(PUBLISH_OPERATION)) {
			return false;
		}
		try {
			for (JsonNode node : mapper.readTree(definition).path("nodes")) {
				if (PUBLISH_OPERATION.equals(node.path("config").path("operation").asText())) {
					return true;
				}
			}
		} catch (Exception e) {
			return false;
		}
		return false;
	}


	private static String limit(String value) {
		String text = value.strip();
		return text.length() <= 255 ? text : text.substring(0, 254) + "…";
	}


	private static long number(Object value) {
		return value == null ? 0 : ((Number) value).longValue();
	}

}
