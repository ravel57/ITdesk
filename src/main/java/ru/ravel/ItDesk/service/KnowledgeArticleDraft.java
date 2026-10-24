package ru.ravel.ItDesk.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Черновик статьи базы знаний из ответа ИИ (операция графа knowledge.draft).
 * <p>
 * Инструкция для ИИ задаётся в графе: ответ в формате «ЗАГОЛОВОК: … / ПРОБЛЕМА: … / РЕШЕНИЕ: …» или словом «НЕТ»,
 * если в переписке нет решения, которое пригодится другим клиентам.
 */
record KnowledgeArticleDraft(String title, List<String> texts) {

	static final int TITLE_LIMIT = 120;
	private static final int MIN_BODY_LENGTH = 40;
	private static final Pattern NO_SOLUTION = Pattern.compile("^[\\s*_\"«]*НЕТ[\\s.!»\"*_]*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern SECTION = Pattern.compile(
			"^[\\s#*_]*(ЗАГОЛОВОК|ПРОБЛЕМА|РЕШЕНИЕ)[\\s*_]*:[\\s*_]*(.*)$",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE);


	/**
	 * Черновик из ответа LLM или пусто, если LLM ответила «НЕТ» или текста статьи не получилось.
	 */
	static Optional<KnowledgeArticleDraft> parse(String answer, String fallbackTitle) {
		if (answer == null || answer.isBlank() || NO_SOLUTION.matcher(answer.strip()).matches()) {
			return Optional.empty();
		}
		String title = null;
		String problem = null;
		StringBuilder solution = null;
		StringBuilder unlabeled = new StringBuilder();
		String current = null;
		for (String rawLine : answer.strip().split("\\R")) {
			String line = cleanup(rawLine);
			Matcher section = SECTION.matcher(rawLine);
			if (section.matches()) {
				current = section.group(1).toUpperCase(Locale.ROOT);
				String value = cleanup(section.group(2));
				switch (current) {
					case "ЗАГОЛОВОК" -> title = value;
					case "ПРОБЛЕМА" -> problem = value;
					default -> solution = new StringBuilder(value);
				}
				continue;
			}
			if (current == null) {
				append(unlabeled, line);
			} else if (current.equals("ПРОБЛЕМА")) {
				problem = problem == null || problem.isEmpty() ? line : (line.isEmpty() ? problem : problem + "\n" + line);
			} else if (current.equals("РЕШЕНИЕ")) {
				append(solution, line);
			}
		}
		List<String> texts = new ArrayList<>();
		if (problem != null && !problem.isBlank()) {
			texts.add("Проблема:\n" + problem.strip());
		}
		if (solution != null && !solution.toString().isBlank()) {
			texts.add("Решение:\n" + solution.toString().strip());
		}
		if (texts.isEmpty() && !unlabeled.toString().isBlank()) {
			texts.add(unlabeled.toString().strip());
		}
		if (texts.stream().mapToInt(String::length).sum() < MIN_BODY_LENGTH) {
			return Optional.empty();
		}
		String finalTitle = title == null || title.isBlank() ? fallbackTitle : title;
		return Optional.of(new KnowledgeArticleDraft(limit(finalTitle == null ? "Черновик статьи" : finalTitle, TITLE_LIMIT), texts));
	}


	private static void append(StringBuilder text, String line) {
		if (line.isEmpty() && (text.isEmpty() || text.charAt(text.length() - 1) == '\n')) {
			return;
		}
		if (!text.isEmpty()) {
			text.append('\n');
		}
		text.append(line);
	}


	/** Без markdown-разметки, которую LLM добавляет вопреки формату. */
	private static String cleanup(String line) {
		return line.replace("**", "").replace("__", "").replaceFirst("^\\s*#+\\s*", "").strip();
	}


	private static String limit(String text, int max) {
		String value = text.strip();
		return value.length() <= max ? value : value.substring(0, max - 1).strip() + "…";
	}

}
