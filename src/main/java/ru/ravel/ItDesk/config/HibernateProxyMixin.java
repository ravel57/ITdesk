package ru.ravel.ItDesk.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


/**
 * Скрывает служебные поля Hibernate-прокси при сериализации сущностей.
 * Прокси появляется, когда сущность (например, SupportLine) уже загружена лениво через другую связь
 * в той же сессии — тогда и EAGER-поле получает тот же прокси.
 */
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public abstract class HibernateProxyMixin {
}
