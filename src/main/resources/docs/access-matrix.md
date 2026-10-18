# Таблица прав ULDesk

Документ генерирует `AccessMatrixTest` из `RolePermissions` и аннотаций контроллеров — руками не править.
Иерархия: ADMIN > MANAGER > SENIOR_OPERATOR > OPERATOR. Разделы OBSERVER (менеджера организации) можно закрыть в настройках: здесь показаны права со всеми открытыми разделами. Ограничение по организациям — `UserService.organizationScope`.

## Права ролей

| Право | Описание | ADMIN | MANAGER | SENIOR_OPERATOR | OPERATOR | OBSERVER | CLIENT |
|---|---|---|---|---|---|---|---|
| `CHAT_VIEW` | Чаты: Просмотр чатов, истории и файлов клиента | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| `CHAT_REPLY` | Чаты: Ответы клиентам, комментарии, шаблоны, связь сообщений с заявками | ✅ | ✅ | ✅ | ✅ | — | — |
| `CHAT_MESSAGE_DELETE` | Чаты: Удаление сообщений | ✅ | ✅ | ✅ | — | — | — |
| `CLIENT_EDIT` | Чаты: Изменение карточки клиента | ✅ | ✅ | ✅ | ✅ | — | — |
| `CLIENT_DELETE` | Чаты: Удаление клиента | ✅ | — | — | — | — | — |
| `CLIENT_IMPORT` | Чаты: Импорт клиентов и их учётных записей из CSV | ✅ | — | — | — | — | — |
| `FILE_VIEW` | Файлы: Просмотр вложений | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `FILE_UPLOAD` | Файлы: Загрузка вложений | ✅ | ✅ | ✅ | ✅ | — | ✅ |
| `TASK_VIEW` | Заявки: Просмотр заявок, истории, файлов и SLA заявки; свои фильтры | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| `TASK_EDIT` | Заявки: Создание и изменение заявок, чат заявки, пауза SLA | ✅ | ✅ | ✅ | ✅ | — | — |
| `TASK_EXPORT` | Заявки: Выгрузка заявок в Excel | ✅ | ✅ | ✅ | — | — | — |
| `GLOBAL_SEARCH` | Заявки: Глобальный поиск | ✅ | ✅ | ✅ | ✅ | — | — |
| `INCIDENT_VIEW` | Инциденты: Просмотр инцидентов | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| `INCIDENT_EDIT` | Инциденты: Создание и ведение инцидентов | ✅ | ✅ | ✅ | ✅ | — | — |
| `INCIDENT_TEMPLATE_MANAGE` | Инциденты: Шаблоны обновлений инцидентов | ✅ | ✅ | — | — | — | — |
| `KB_VIEW` | База знаний: Чтение базы знаний | ✅ | ✅ | ✅ | ✅ | — | — |
| `KB_EDIT` | База знаний: Изменение базы знаний | ✅ | ✅ | ✅ | — | — | — |
| `LLM_MANAGE` | База знаний: Переобучение LLM | ✅ | — | — | — | — | — |
| `SERVICE_VIEW` | Сервисы: Каталог услуг и услуги организаций | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| `SERVICE_MANAGE` | Сервисы: Изменение каталога услуг, критичностей и назначений организациям | ✅ | ✅ | — | — | — | — |
| `MONITORING_USE` | Сервисы: Сводка мониторинга и запуск проверки услуги | ✅ | ✅ | ✅ | ✅ | — | — |
| `MONITORING_MANAGE` | Сервисы: Интеграции Zabbix | ✅ | ✅ | — | — | — | — |
| `REFERENCE_VIEW` | Справочники: Статусы, приоритеты, теги, типы заявок, линии поддержки, организации | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| `STAFF_DIRECTORY_VIEW` | Справочники: Справочники сотрудников: критичности, причины выездов, каналы, сотрудники, исполнители линий | ✅ | ✅ | ✅ | ✅ | — | — |
| `REFERENCE_MANAGE` | Справочники: Изменение справочников: статусы, приоритеты, шаблоны, теги, типы заявок, линии, причины выездов | ✅ | ✅ | — | — | — | — |
| `ORGANIZATION_MANAGE` | Справочники: Изменение организаций | ✅ | ✅ | — | — | — | — |
| `ORGANIZATION_STATS` | Справочники: Статистика заявок по организациям | ✅ | ✅ | ✅ | ✅ | — | — |
| `ORGANIZATION_VISITS` | Справочники: Выезды в организации | ✅ | ✅ | ✅ | ✅ | — | — |
| `SLA_VIEW` | SLA и аналитика: Матрица SLA | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| `SLA_MANAGE` | SLA и аналитика: Изменение матрицы SLA | ✅ | ✅ | — | — | — | — |
| `ANALYTICS_VIEW` | SLA и аналитика: Аналитика | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| `AUTOMATION_MANAGE` | Автоматизации: Триггеры, запуски, вебхуки | ✅ | ✅ | — | — | — | — |
| `AUTOMATION_APPROVE` | Автоматизации: Одобрение и отклонение запусков | ✅ | ✅ | ✅ | ✅ | — | — |
| `PLUGIN_USE` | Плагины: Расширения интерфейса от плагинов | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| `PLUGIN_MANAGE` | Плагины: Установка и настройка плагинов | ✅ | — | — | — | — | — |
| `NOTIFICATIONS` | Профиль: Свои уведомления | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| `SETTINGS_VIEW` | Администрирование: Просмотр общих настроек | ✅ | ✅ | — | — | — | — |
| `SETTINGS_MANAGE` | Администрирование: Общие настройки, лицензия, индекс поиска | ✅ | — | — | — | — | — |
| `USER_MANAGE` | Администрирование: Пользователи и роли | ✅ | — | — | — | — | — |
| `CHANNEL_MANAGE` | Администрирование: Каналы связи: Telegram, WhatsApp, Email | ✅ | — | — | — | — | — |
| `JWT_MANAGE` | Администрирование: Ключи API (JWT) | ✅ | — | — | — | — | — |
| `SECURITY_AUDIT_VIEW` | Администрирование: Журнал безопасности | ✅ | — | — | — | — | — |
| `PORTAL` | Клиентский портал: Клиентский портал | — | — | — | — | — | ✅ |

## Адреса API

| Метод | Адрес | Проверка | ADMIN | MANAGER | SENIOR_OPERATOR | OPERATOR | OBSERVER | CLIENT |
|---|---|---|---|---|---|---|---|---|
| GET | `/` | страницы SPA | · | · | · | · | · | · |
| GET | `/api/v1/analytics/dictionaries` | can('ANALYTICS_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| GET | `/api/v1/analytics/summary` | can('ANALYTICS_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/analytics/summary/cancel` | can('ANALYTICS_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/auth/mfa/disable` | isAuthenticated() | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| POST | `/api/v1/auth/mfa/enroll/confirm` | isAuthenticated() | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| POST | `/api/v1/auth/mfa/enroll/start` | isAuthenticated() | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| GET | `/api/v1/auth/mfa/status` | isAuthenticated() | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| POST | `/api/v1/auth/mfa/verify` | isAuthenticated() | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| GET | `/api/v1/automation/expression/roots` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/automation/expression/suggestions` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/automation/runs` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/automation/runs/{runId}` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/automation/runs/{runId}/approve` | can('AUTOMATION_APPROVE') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/automation/runs/{runId}/reject` | can('AUTOMATION_APPROVE') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/automation/runs/{runId}/retry` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/automation/test` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/automation/validate` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/client-files/{clientId}` | can('CHAT_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/client-users/import-csv` | can('CLIENT_IMPORT') | ✅ | — | — | — | — | — |
| POST | `/api/v1/client-users/import-csv/preview` | can('CLIENT_IMPORT') | ✅ | — | — | — | — | — |
| GET | `/api/v1/client-users/import-csv/template` | can('CLIENT_IMPORT') | ✅ | — | — | — | — | — |
| PATCH | `/api/v1/client/incident/{incidentId}/recovery-confirmation` | can('PORTAL') | — | — | — | — | — | ✅ |
| GET | `/api/v1/client/incidents/active` | can('PORTAL') | — | — | — | — | — | ✅ |
| DELETE | `/api/v1/client/{clientId}` | can('CLIENT_DELETE') | ✅ | — | — | — | — | — |
| PATCH | `/api/v1/client/{clientId}/client` | can('CLIENT_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| DELETE | `/api/v1/client/{clientId}/delete-message/{messageId}` | can('CHAT_MESSAGE_DELETE') | ✅ | ✅ | ✅ | — | — | — |
| POST | `/api/v1/client/{clientId}/link-message-to-task` | can('CHAT_REPLY') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/client/{clientId}/linked-message` | can('CHAT_REPLY') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/client/{clientId}/message` | can('CHAT_REPLY') | ✅ | ✅ | ✅ | ✅ | — | — |
| PATCH | `/api/v1/client/{clientId}/message/{messageId}` | can('CHAT_REPLY') | ✅ | ✅ | ✅ | ✅ | — | — |
| PATCH | `/api/v1/client/{clientId}/message/{messageId}/answer-required` | can('CHAT_REPLY') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/client/{clientId}/messages-page` | can('CHAT_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/client/{clientId}/search-messages` | can('CHAT_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| PATCH | `/api/v1/client/{clientId}/task` | can('TASK_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/client/{clientId}/task` | can('TASK_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/client/{clientId}/task-pings` | can('TASK_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/client/{clientId}/task/{taskId}/files` | can('TASK_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/client/{clientId}/task/{taskId}/mark-message-read` | can('TASK_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/client/{clientId}/task/{taskId}/message` | can('TASK_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| PATCH | `/api/v1/client/{clientId}/task/{taskId}/message/{messageId}` | can('TASK_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/client/{clientId}/task/{taskId}/search-messages` | can('TASK_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/client/{clientId}/task/{taskId}/sla/info` | can('TASK_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/client/{clientId}/task/{taskId}/sla/pause` | can('TASK_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/client/{clientId}/task/{taskId}/sla/resume` | can('TASK_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/clients` | can('CHAT_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/clients/import-csv` | can('CLIENT_IMPORT') | ✅ | — | — | — | — | — |
| GET | `/api/v1/clients/import-csv/template` | can('CLIENT_IMPORT') | ✅ | — | — | — | — | — |
| DELETE | `/api/v1/delete-user/{userId}` | can('USER_MANAGE') | ✅ | — | — | — | — | — |
| PATCH | `/api/v1/email` | can('CHANNEL_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/email` | can('CHANNEL_MANAGE') | ✅ | — | — | — | — | — |
| DELETE | `/api/v1/email/{emailAccountId}` | can('CHANNEL_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/emails` | can('STAFF_DIRECTORY_VIEW') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/export/to-excel` | can('TASK_EXPORT') | ✅ | ✅ | ✅ | — | — | — |
| POST | `/api/v1/filter` | can('TASK_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| DELETE | `/api/v1/filter/{filterId}` | can('TASK_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| GET | `/api/v1/filters` | can('TASK_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/get-authenticated-users` | can('STAFF_DIRECTORY_VIEW') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/global-search` | can('GLOBAL_SEARCH') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/global-search/count` | can('SETTINGS_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/global-search/reindex` | can('SETTINGS_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/incident` | can('INCIDENT_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/incident-update-template` | can('INCIDENT_TEMPLATE_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/incident-update-template/{templateId}` | can('INCIDENT_TEMPLATE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/incident-update-template/{templateId}` | can('INCIDENT_TEMPLATE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/incident-update-templates` | can('INCIDENT_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| PATCH | `/api/v1/incident-update-templates/resort` | can('INCIDENT_TEMPLATE_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/incident/from-tasks` | can('INCIDENT_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/incident/{incidentId}` | can('INCIDENT_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| PATCH | `/api/v1/incident/{incidentId}` | can('INCIDENT_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/incident/{incidentId}/suggestions` | can('INCIDENT_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| DELETE | `/api/v1/incident/{incidentId}/task/{taskId}` | can('INCIDENT_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| PATCH | `/api/v1/incident/{incidentId}/task/{taskId}/recovery-confirmation` | can('INCIDENT_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/incident/{incidentId}/tasks` | can('INCIDENT_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/incident/{incidentId}/tasks/bulk` | can('INCIDENT_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/incident/{incidentId}/timeline` | can('INCIDENT_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/incidents-page` | can('INCIDENT_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/integrations/zabbix/{key}/events` | Zabbix: токен интеграции | · | · | · | · | · | · |
| GET,POST | `/api/v1/integrations/zabbix/{key}/heartbeat` | Zabbix: токен интеграции | · | · | · | · | · | · |
| POST | `/api/v1/jwt/generate` | can('JWT_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/jwt/keys` | can('JWT_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/jwt/keys/rotate` | can('JWT_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/jwt/scopes` | can('JWT_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/jwt/tokens` | can('JWT_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/jwt/tokens/revoke-all` | can('JWT_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/jwt/tokens/{tokenId}/revoke` | can('JWT_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/license-info` | can('SETTINGS_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/llm/kb-count` | can('LLM_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/llm/query` | can('CHAT_REPLY') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/llm/retrain` | can('LLM_MANAGE') | ✅ | — | — | — | — | — |
| PATCH | `/api/v1/organization` | can('ORGANIZATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/organization` | can('ORGANIZATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/organization-service-assignment` | can('SERVICE_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/organization-service-assignment/{assignmentId}` | can('SERVICE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/organization-service-assignment/{assignmentId}` | can('SERVICE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/organization-service-assignments` | can('SERVICE_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/organization/{organizationId}` | can('ORGANIZATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/organization/{organizationId}/services` | canAny('SERVICE_VIEW', 'PORTAL') | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| GET | `/api/v1/organization/{organizationId}/visits` | can('ORGANIZATION_VISITS') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/organization/{organizationId}/visits` | can('ORGANIZATION_VISITS') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/organizations` | can('REFERENCE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| PATCH | `/api/v1/organizations/resort` | can('ORGANIZATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/organizations/task-stats` | can('ORGANIZATION_STATS') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/permissions` | can('USER_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/plugins` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/plugins/frontend-schema` | can('PLUGIN_USE') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/plugins/install` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/plugins/native-hook/execute` | can('PLUGIN_USE') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/plugins/reload` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| DELETE | `/api/v1/plugins/{pluginKey}` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/plugins/{pluginKey}/disable` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/plugins/{pluginKey}/enable` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/plugins/{pluginKey}/permissions` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/plugins/{pluginKey}/permissions/approve` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/plugins/{pluginKey}/permissions/revoke` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/plugins/{pluginKey}/reload` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/plugins/{pluginKey}/secrets` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| DELETE | `/api/v1/plugins/{pluginKey}/secrets/{name}` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| PUT | `/api/v1/plugins/{pluginKey}/secrets/{name}` | can('PLUGIN_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/portal/messages` | can('PORTAL') | — | — | — | — | — | ✅ |
| POST | `/api/v1/portal/messages` | can('PORTAL') | — | — | — | — | — | ✅ |
| GET | `/api/v1/portal/profile` | can('PORTAL') | — | — | — | — | — | ✅ |
| GET | `/api/v1/priorities` | can('REFERENCE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| PATCH | `/api/v1/priorities/resort` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/priority` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/priority` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/priority/set-default` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/priority/set-high-priority` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/priority/{priorityId}` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/roles` | can('USER_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/security-audit` | can('SECURITY_AUDIT_VIEW') | ✅ | — | — | — | — | — |
| GET | `/api/v1/security-audit/types` | can('SECURITY_AUDIT_VIEW') | ✅ | — | — | — | — | — |
| POST | `/api/v1/service` | can('SERVICE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/service-criticalities` | can('STAFF_DIRECTORY_VIEW') | ✅ | ✅ | ✅ | ✅ | — | — |
| PATCH | `/api/v1/service-criticalities/resort` | can('SERVICE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/service-criticality` | can('SERVICE_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/service-criticality` | can('SERVICE_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/service-criticality/{criticalityId}` | can('SERVICE_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/service/{serviceId}` | can('SERVICE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/service/{serviceId}` | can('SERVICE_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/service/{serviceId}/monitoring/check` | can('MONITORING_USE') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/services` | can('SERVICE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| GET | `/api/v1/services/monitoring/summary` | can('MONITORING_USE') | ✅ | ✅ | ✅ | ✅ | — | — |
| PATCH | `/api/v1/services/resort` | can('SERVICE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/services/{serviceId}` | can('SERVICE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| GET | `/api/v1/session/current` | isAuthenticated() | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| GET | `/api/v1/settings/general` | can('SETTINGS_VIEW') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/settings/general` | can('SETTINGS_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/settings/jev` | can('SETTINGS_MANAGE') | ✅ | — | — | — | — | — |
| PATCH | `/api/v1/settings/jev` | can('SETTINGS_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/settings/jev/decisions` | can('SETTINGS_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/settings/jev/decisions/object` | can('SETTINGS_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/settings/jev/decisions/stats` | can('SETTINGS_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/settings/observer-access` | can('SETTINGS_VIEW') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/settings/observer-access` | can('SETTINGS_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/settings/security` | can('SETTINGS_VIEW') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/settings/security` | can('SETTINGS_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/sla` | can('SLA_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/sla` | can('SLA_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/status` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/status` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/status/resort` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/status/set-default` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/status/{statusId}` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/statuses` | can('REFERENCE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| PATCH | `/api/v1/support-line` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/support-line` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/support-line/set-default` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/support-line/{supportLineId}` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/support-lines` | can('REFERENCE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| PATCH | `/api/v1/support-lines/resort` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/support-lines/{supportLineId}/assignable-users` | can('STAFF_DIRECTORY_VIEW') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/support/reset-password` | без входа: запрос ссылки смены пароля | · | · | · | · | · | · |
| POST | `/api/v1/support/reset-password/confirm` | без входа: новый пароль по ссылке | · | · | · | · | · | · |
| POST | `/api/v1/support/reset-password/verify` | без входа: проверка ссылки | · | · | · | · | · | · |
| PATCH | `/api/v1/tag` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/tag` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/tag/{tagId}` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/tags` | can('REFERENCE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| PATCH | `/api/v1/tags/resort` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/task-types` | can('REFERENCE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/task-types` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/task-types/resort` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/task-types/set-default` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/task-types/{id}` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/task-types/{id}` | can('REFERENCE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| PATCH | `/api/v1/task-types/{id}` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/task/{taskId}/history` | can('TASK_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| GET | `/api/v1/task/{taskId}/incident` | can('INCIDENT_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| GET | `/api/v1/task/{taskId}/sla/info` | can('TASK_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| POST | `/api/v1/task/{taskId}/sla/pause` | can('TASK_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/task/{taskId}/sla/resume` | can('TASK_EDIT') | ✅ | ✅ | ✅ | ✅ | — | — |
| POST | `/api/v1/tasks-page` | can('TASK_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| PATCH | `/api/v1/telegram-bot` | can('CHANNEL_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/telegram-bot` | can('CHANNEL_MANAGE') | ✅ | — | — | — | — | — |
| DELETE | `/api/v1/telegram-bot/{tgBotId}` | can('CHANNEL_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/telegram-bots` | can('STAFF_DIRECTORY_VIEW') | ✅ | ✅ | ✅ | ✅ | — | — |
| PATCH | `/api/v1/template` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/template` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/template/{templateId}` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/templates` | can('CHAT_REPLY') | ✅ | ✅ | ✅ | ✅ | — | — |
| PATCH | `/api/v1/templates/resort` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/trigger` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/trigger` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/trigger-types` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/trigger-types/options` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/trigger/{triggerId}` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/triggers` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/triggers/resort` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/user` | can('USER_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/user` | can('USER_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/user-offline` | любой вошедший | · | · | · | · | · | · |
| POST | `/api/v1/user-online` | любой вошедший | · | · | · | · | · | · |
| POST | `/api/v1/user/change-password` | любой вошедший: свой пароль | · | · | · | · | · | · |
| GET | `/api/v1/user/current` | isAuthenticated() | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| GET | `/api/v1/user/notification-settings` | can('NOTIFICATIONS') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| PATCH | `/api/v1/user/notification-settings` | can('NOTIFICATIONS') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| GET | `/api/v1/user/notifications` | can('NOTIFICATIONS') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| PATCH | `/api/v1/user/notifications/read-all` | can('NOTIFICATIONS') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| PATCH | `/api/v1/user/notifications/{notificationId}/read` | can('NOTIFICATIONS') | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| GET | `/api/v1/user/permissions` | isAuthenticated() | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| POST | `/api/v1/user/{userId}/change-password` | can('USER_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/user/{userId}/mfa/reset` | can('USER_MANAGE') | ✅ | — | — | — | — | — |
| GET | `/api/v1/users` | can('STAFF_DIRECTORY_VIEW') | ✅ | ✅ | ✅ | ✅ | — | — |
| GET | `/api/v1/users/manage` | can('USER_MANAGE') | ✅ | — | — | — | — | — |
| PATCH | `/api/v1/visit-reason` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/visit-reason` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/visit-reason/{reasonId}` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/visit-reasons` | can('STAFF_DIRECTORY_VIEW') | ✅ | ✅ | ✅ | ✅ | — | — |
| PATCH | `/api/v1/visit-reasons/resort` | can('REFERENCE_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/webhooks/test` | can('AUTOMATION_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/whatsapp` | can('STAFF_DIRECTORY_VIEW') | ✅ | ✅ | ✅ | ✅ | — | — |
| PATCH | `/api/v1/whatsapp` | can('CHANNEL_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/whatsapp` | can('CHANNEL_MANAGE') | ✅ | — | — | — | — | — |
| DELETE | `/api/v1/whatsapp/{accountId}` | can('CHANNEL_MANAGE') | ✅ | — | — | — | — | — |
| POST | `/api/v1/zabbix-integration` | can('MONITORING_MANAGE') | ✅ | ✅ | — | — | — | — |
| DELETE | `/api/v1/zabbix-integration/{integrationId}` | can('MONITORING_MANAGE') | ✅ | ✅ | — | — | — | — |
| PATCH | `/api/v1/zabbix-integration/{integrationId}` | can('MONITORING_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/zabbix-integration/{integrationId}/media-type` | can('MONITORING_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/zabbix-integration/{integrationId}/test` | can('MONITORING_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/zabbix-integration/{integrationId}/webhook-log` | can('MONITORING_MANAGE') | ✅ | ✅ | — | — | — | — |
| POST | `/api/v1/zabbix-integration/{integrationId}/webhook-token` | can('MONITORING_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1/zabbix-integrations` | can('MONITORING_MANAGE') | ✅ | ✅ | — | — | — | — |
| GET | `/api/v1knowledge-base` | can('KB_VIEW') | ✅ | ✅ | ✅ | ✅ | — | — |
| PATCH | `/api/v1knowledge-base` | can('KB_EDIT') | ✅ | ✅ | ✅ | — | — | — |
| POST | `/api/v1knowledge-base` | can('KB_EDIT') | ✅ | ✅ | ✅ | — | — | — |
| POST | `/api/v1knowledge-base/resort` | can('KB_EDIT') | ✅ | ✅ | ✅ | — | — | — |
| DELETE | `/api/v1knowledge-base/{knowledgeId}` | can('KB_EDIT') | ✅ | ✅ | ✅ | — | — | — |
| GET | `/csrf` | любой, CSRF-токен | · | · | · | · | · | · |
| GET | `/files/audios/{uuid}` | can('FILE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| GET | `/files/documents/{uuid}` | can('FILE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| GET | `/files/images/{uuid}` | can('FILE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| POST | `/files/upload` | can('FILE_UPLOAD') | ✅ | ✅ | ✅ | ✅ | — | ✅ |
| GET | `/files/videos/{uuid}` | can('FILE_VIEW') | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
