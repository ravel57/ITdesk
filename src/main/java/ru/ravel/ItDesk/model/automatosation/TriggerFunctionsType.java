package ru.ravel.ItDesk.model.automatosation;

import lombok.Getter;

@Getter
public enum TriggerFunctionsType {
	STARTS_WITH("startsWith"),
	ENDS_WITH("endsWith"),

	CONTAINS("contains"),
	CONTAINS_ANY("containsAny"),
	CONTAINS_ALL("containsAll"),

	ANY_OF("anyOf"),
	NONE_OF("noneOf"),
	ALL_OF("allOf"),

	IS_NULL("isNull"),
	NOT_NULL("notNull"),
	IS_EMPTY("isEmpty"),
	NOT_EMPTY("notEmpty"),
	IS_TRUE("isTrue"),
	IS_FALSE("isFalse"),

	NOW("now"),
	HOUR("hour"),
	DAY_OF_WEEK("day_of_week"),
	IS_WEEKEND("is_weekend"),
	IS_WORKING_HOURS("is_working_hours"),
	IS_AFTER_HOURS("is_after_hours"),

	LOWER("lower"),
	UPPER("upper"),
	TRIM("trim"),
	LENGTH("length"),
	LAST("last"),

	DAYS_BETWEEN("daysBetween"),
	MINUTES_BETWEEN("minutesBetween"),

	HAS_TAG("hasTag"),
	NO_OPEN_TASKS("noOpenTasks"),

	EQUALS_IGNORE_CASE("equalsIgnoreCase"),
	MATCHES("matches"),
	CONTAINS_REGEX("containsRegex"),

	HAS_OPEN_TASKS("hasOpenTasks"),
	OPEN_TASKS_COUNT("openTasksCount"),
	MESSAGES_COUNT("messagesCount"),
	INCOME_MESSAGES_COUNT("incomeMessagesCount"),
	OUTCOME_MESSAGES_COUNT("outcomeMessagesCount"),

	IS_FIRST_MESSAGE("isFirstMessage"),
	IS_REPEAT_MESSAGE("isRepeatMessage"),

	HAS_ATTACHMENT("hasAttachment"),
	IS_IMAGE("isImage"),
	IS_DOCUMENT("isDocument"),

	DAYS_SINCE("daysSince"),
	MINUTES_SINCE("minutesSince"),

	IS_TODAY("isToday"),
	IS_BEFORE("isBefore"),
	IS_AFTER("isAfter"),

	IS_TELEGRAM("isTelegram"),
	IS_EMAIL("isEmail"),
	IS_WHATSAPP("isWhatsapp")
	;

	private final String operator;

	TriggerFunctionsType(String operator) {
		this.operator = operator;
	}
}