# Плагины: capability-модель, сеть и секреты

Плагины пишут и устанавливают пользователи системы. Поэтому ядро не знает
адресов их API, имён токенов и пользовательских URI-схем. Всё это описывает
сам плагин, а администратор конкретной установки решает, какие capabilities
одобрить.

## Базовый принцип

Установка плагина **не выдаёт ему прав**. `manifest.yml` является заявкой.
Каждая capability сохраняется как `approved=false` и начинает действовать
только после явного решения администратора.

При обновлении плагина неизменившаяся строка permission сохраняет прежнее
решение. Новая или изменённая строка снова требует одобрения. Поэтому подмена
или обновление ZIP не может молча расширить доступ.

## Permissions

Поддерживаются четыре типа прав:

| Формат | Назначение |
|---|---|
| `<resource>:read` | Читать данные текущей сущности этого типа в sandbox context, например `client:read` |
| `http:<method>:<url-prefix>` | Выполнять исходящие HTTP(S)-запросы только данным методом и только внутри указанного origin/path-prefix |
| `secret:<name>` | Использовать секрет этого плагина; значение задаёт администратор и код плагина его не получает |
| `url-scheme:<scheme>` | Открывать пользовательскую URI-схему из declarative frontend |

Имена ресурсов не зашиты в permission parser. Если ядро в будущем добавляет
новый entity context, например `asset`, для него естественно используется
`asset:read` без добавления очередного plugin-specific permission.

Опасные browser-схемы (`javascript`, `data`, `file`, `vbscript`, `blob`)
отклоняются при загрузке манифеста. Остальные пользовательские схемы не требуют
списка в `application.yaml`: разрешается только конкретная схема, которую
плагин запросил и администратор одобрил.

### HTTP prefixes

Пример:

```yaml
permissions:
  - client:read
  - http:get:https://api.example.com/v1/customers/
  - http:post:https://api.example.com/v1/tickets/
```

Префикс обязан заканчиваться `/` и не может содержать user-info, query или
fragment. Право на `https://api.example.com/v1/` не даёт доступ к
`https://api.example.com/admin/` или другому origin.

По умолчанию публичный HTTPS не требует vendor allowlist адресов: достаточно
точного permission и admin approval. `PLUGIN_HTTP_ALLOWED_PREFIXES` остаётся
необязательным **операторским потолком**, если владелец конкретной инсталляции
хочет дополнительно сузить egress.

SSRF-защита, запрет приватных/служебных IP и plaintext остаются включёнными по
умолчанию. Их operator-настройки не содержат адресов конкретных плагинов.

## Секреты без токенов в application.yaml

Plugin-specific токенов в `application.yaml` нет. Плагин объявляет только
метаданные секрета и место его безопасной инъекции:

```yaml
permissions:
  - http:get:https://api.example.com/v1/
  - secret:example-token

secrets:
  - name: example-token
    title: API token
    description: Создайте токен в кабинете Example
    injection: header
    parameter: Authorization
    valuePrefix: "Bearer "
    sendTo:
      - https://api.example.com/v1/
```

Для каждой `secret:<name>` декларация `secrets:` обязательна, и наоборот.
Каждый `sendTo` обязан находиться внутри одного из HTTP-prefix, которые сам
плагин запросил. Это проверяется ещё при загрузке ZIP.

Администратор задаёт значение через API. Оно хранится в таблице `plugin_secret`
в зашифрованном виде и привязано одновременно к `pluginKey` и `name`.
Значение не попадает в manifest, Groovy-код, stdin плагина или его environment.

Единственный системный ключ — `ULDESK_PLUGIN_SECRET_KEY`: это не токен
интеграции, а master key конкретной установки для AES-GCM шифрования plugin
secrets. Он хранится вне БД.

Broker автоматически добавляет секрет только когда одновременно выполнено всё:

1. плагин запросил `secret:<name>`;
2. администратор одобрил это право;
3. значение задано для **этого pluginKey**;
4. запрос разрешён отдельным `http:*` permission;
5. URL запроса попадает в `secrets[].sendTo`.

Legacy `querySecrets` не является обходом: он допускается только если совпадает
с объявленным `injection: query`, `parameter`, secret name и `sendTo`.

## Данные системы

`client:read` — реальная capability. Если она не одобрена, расширение с
`entityType: CLIENT` не выдаётся frontend, а sandbox получает пустой `entity`
вместо данных клиента. Аналогично работает любой `<resource>:read` для entity
context, который формирует ядро.

Это отделяет право «показать/запустить плагин» от фактического доступа к PII.
Проверка штатной авторизации пользователя к самой карточке клиента выполняется
ещё до формирования plugin context.

## Declarative frontend и пользовательские URI-схемы

Пример:

```yaml
permissions:
  - client:read
  - url-scheme:my-desktop-app
```

В `ui.yml` плагин может использовать `my-desktop-app://...`. Backend всегда
перезаписывает служебный `__allowedUrlSchemes` и помещает туда только
**одобренные** `url-scheme:*`. Значение, подложенное самим plugin UI YAML,
игнорируется.

Отдельный server-side allowlist пользовательских URI-схем для каждого нового
аддона не требуется.

## Sandbox

Для недоверенных пользовательских плагинов production-профиль остаётся
fail-closed:

```text
PLUGIN_SANDBOX_MODE=strict
PLUGIN_SANDBOX_ALLOW_DEGRADED=false
```

В strict-режиме Groovy выполняется в отдельном bubblewrap namespace без host
network, writable application filesystem и inherited environment. Единственный
сетевой путь — Unix socket broker с короткоживущим capability token.

Broker дополнительно ограничивает число запросов, размер request/response,
timeout, HTTP methods, content types и не следует redirect автоматически.

## API администратора

```text
GET    /api/v1/plugins/{key}/permissions
POST   /api/v1/plugins/{key}/permissions/approve
POST   /api/v1/plugins/{key}/permissions/revoke

GET    /api/v1/plugins/{key}/secrets
PUT    /api/v1/plugins/{key}/secrets/{name}
DELETE /api/v1/plugins/{key}/secrets/{name}
```

`PUT secret` принимает только имя, которое присутствует в `secrets:`
загруженного manifest. Произвольный скрытый secret нельзя создать через API.

## Что допустимо конфигурировать в application.yaml

Системная конфигурация задаёт только общую политику исполнения, а не сведения о
конкретных аддонах:

* каталог plugins;
* strict sandbox и лимиты;
* необязательный общий egress ceiling / deny-list;
* запрет private targets и plaintext по умолчанию;
* HTTP timeout/size/request quotas;
* master key для шифрования plugin secrets.

Адреса API, URI-схемы, имена и значения токенов конкретных плагинов там не
должны появляться.
