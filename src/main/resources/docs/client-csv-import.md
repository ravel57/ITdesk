# Импорт клиентских учётных записей из CSV / Active Directory

## Что поддерживается

Во вкладке **Настройки → Клиенты** администратор может:

- загрузить CSV и получить предпросмотр до создания пользователей;
- отредактировать значения прямо в таблице;
- массово добавить домен к коротким AD-логинам (`ivanov` → `ivanov@company.ru`);
- массово назначить организацию;
- выбрать организацию только из уже существующих организаций;
- импортировать клиентские учётные записи со сгенерированными паролями;
- сразу после импорта скачать CSV с логинами и сгенерированными паролями;
- отдельно сменить пароль существующему клиенту.

Пароли импортированных пользователей возвращаются только в ответе операции импорта. В базе хранится только хеш пароля.

## CSV-формат учётных записей

Рекомендуемые колонки:

```text
username,firstname,lastname,email,phoneNumber,organization,moreInfo
```

Распознаются также стандартные поля Active Directory: `sAMAccountName`, `UserPrincipalName`, `GivenName`, `Surname`, `mail`, `telephoneNumber`, `mobile`, `company`, `department`, `title`, `description`.

Кодировка: UTF-8. Разделитель может быть `,` или `;`. Quoted-поля и переносы строк внутри quoted-полей поддерживаются.

## API

### Предпросмотр

```http
POST /api/v1/client-users/import-csv/preview
Content-Type: multipart/form-data
```

Параметр `file` содержит CSV. Endpoint ничего не создаёт и возвращает нормализованные строки с `READY`, `WARNING` или `ERROR`.

### Импорт после редактирования

```http
POST /api/v1/client-users/import-csv
Content-Type: application/json
```

Тело содержит отредактированные строки. Организация каждой импортируемой учётной записи должна уже существовать в системе.

Для каждой новой учётной записи сервер генерирует стойкий случайный пароль. В response поле `credentials` содержит логин и пароль только для пользователей, созданных этим запросом. Существующие логины пропускаются и их пароли не меняются.

### Шаблон

```http
GET /api/v1/client-users/import-csv/template
```

### Смена пароля клиента администратором

```http
POST /api/v1/user/{userId}/change-password
Content-Type: application/json

{"password":"new-password"}
```

Endpoint доступен только роли `ADMIN` и принимает только клиентские учётные записи.

## Выгрузка пользователей из Active Directory

Запустите PowerShell на компьютере с установленным RSAT / модулем **ActiveDirectory**:

```powershell
Import-Module ActiveDirectory

Get-ADUser -Filter 'Enabled -eq $true' -Properties GivenName,Surname,mail,telephoneNumber,Company,Department,Title |
  Select-Object @{N='username';E={$_.SamAccountName}},
                @{N='firstname';E={$_.GivenName}},
                @{N='lastname';E={$_.Surname}},
                @{N='email';E={$_.mail}},
                @{N='phoneNumber';E={$_.telephoneNumber}},
                @{N='organization';E={if ($_.Company) {$_.Company} else {$_.Department}}},
                @{N='moreInfo';E={$_.Title}} |
  Export-Csv -Path '.\client-users.csv' -NoTypeInformation -Encoding UTF8
```

После загрузки `client-users.csv` короткие `sAMAccountName` можно массово дополнить доменом прямо в предпросмотре.

### Только конкретный OU

```powershell
Get-ADUser -SearchBase 'OU=Users,DC=company,DC=local' -Filter 'Enabled -eq $true' `
  -Properties GivenName,Surname,mail,telephoneNumber,Company,Department,Title
```
