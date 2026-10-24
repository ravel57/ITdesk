package ru.ravel.ItDesk.dto;

import java.util.List;


/**
 * Статистика подсказок за период по правилам и источникам и список правил, которые сейчас публикуют подсказки
 * (в графе есть шаг «Опубликовать подсказку оператору»).
 */
public record OperatorHintStatsDto(List<Rule> rules, List<RuleRef> publishingRules) {

	/**
	 * Правило с подсказками за период. name — текущее название, а для удалённого правила — название на момент
	 * последней подсказки; exists=false — правило удалено.
	 */
	public record Rule(Long ruleId, String name, boolean exists, boolean enabled, List<Source> sources) {
	}

	public record Source(String source, String label, long total, long helpful, long notHelpful, Double averageProbability) {
	}

	public record RuleRef(Long id, String name, boolean enabled) {
	}
}
