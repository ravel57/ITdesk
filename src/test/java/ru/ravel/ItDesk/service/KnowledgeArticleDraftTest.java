package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


class KnowledgeArticleDraftTest {

	@Test
	void parsesSectionsAndStripsMarkdown() {
		KnowledgeArticleDraft draft = KnowledgeArticleDraft.parse("""
				**ЗАГОЛОВОК:** Не печатает сетевой принтер
				## ПРОБЛЕМА: Задания зависают в очереди
				и не печатаются.
				РЕШЕНИЕ:
				1. Очистите очередь печати.

				2. Перезапустите службу печати.
				""", "Запасной").orElseThrow();

		assertThat(draft.title()).isEqualTo("Не печатает сетевой принтер");
		assertThat(draft.texts()).containsExactly(
				"Проблема:\nЗадания зависают в очереди\nи не печатаются.",
				"Решение:\n1. Очистите очередь печати.\n\n2. Перезапустите службу печати.");
	}


	@Test
	void answerNoMeansNoDraft() {
		for (String answer : List.of("НЕТ", "нет.", "**НЕТ**", " «Нет» ", "")) {
			assertThat(KnowledgeArticleDraft.parse(answer, "Заявка")).as(answer).isEmpty();
		}
	}


	@Test
	void freeTextWithoutSectionsUsesFallbackTitle() {
		KnowledgeArticleDraft draft = KnowledgeArticleDraft.parse(
				"Чтобы восстановить доступ к почте, сбросьте пароль в личном кабинете и войдите заново.", "Нет доступа к почте").orElseThrow();
		assertThat(draft.title()).isEqualTo("Нет доступа к почте");
		assertThat(draft.texts()).hasSize(1);
	}


	@Test
	void tooShortTextAndLongTitleAreHandled() {
		assertThat(KnowledgeArticleDraft.parse("ЗАГОЛОВОК: Тест\nРЕШЕНИЕ: ок", "Заявка")).isEmpty();
		KnowledgeArticleDraft draft = KnowledgeArticleDraft.parse("ЗАГОЛОВОК: " + "Очень длинный заголовок ".repeat(10)
				+ "\nРЕШЕНИЕ: Перезагрузите компьютер и проверьте подключение к сети.", "Заявка").orElseThrow();
		assertThat(draft.title()).hasSizeLessThanOrEqualTo(KnowledgeArticleDraft.TITLE_LIMIT).endsWith("…");
	}

}
