package ru.ravel.ItDesk.model;


/**
 * Состояние статьи базы знаний. Черновик видят только редакторы базы знаний: его не подбирает Jev, не получает LLM
 * и не находит глобальный поиск, пока статью не опубликуют.
 */
public enum KnowledgeStatus {
	DRAFT,
	PUBLISHED
}
