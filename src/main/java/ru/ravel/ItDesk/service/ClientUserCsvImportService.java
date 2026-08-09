package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.ravel.ItDesk.dto.*;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ClientUserCsvImportService {

    private static final String USER_IMPORT_TEMPLATE = "username,firstname,lastname,email,phoneNumber,organization,moreInfo\r\n";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    private static final String PASSWORD_LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String PASSWORD_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String PASSWORD_DIGITS = "23456789";
    private static final String PASSWORD_SPECIAL = "!@#$%*-_";
    private static final String PASSWORD_ALL = PASSWORD_LOWER + PASSWORD_UPPER + PASSWORD_DIGITS + PASSWORD_SPECIAL;

    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final OrganizationService organizationService;
    private final UserService userService;
    private final SecureRandom secureRandom = new SecureRandom();

    public byte[] getTemplate() {
        return USER_IMPORT_TEMPLATE.getBytes(StandardCharsets.UTF_8);
    }

    public ClientUserCsvPreview preview(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("CSV-файл пуст или не передан");
        }

        List<List<String>> csvRows = parseCsv(file);
        if (csvRows.isEmpty()) {
            throw new IllegalArgumentException("CSV-файл не содержит строк");
        }

        Map<String, Integer> columns = indexColumns(csvRows.getFirst());
        validateHeader(columns);
        Set<String> organizationNames = organizationService.getOrganizations().stream()
                .map(Organization::getName)
                .filter(Objects::nonNull)
                .map(this::normalizeKey)
                .collect(java.util.stream.Collectors.toSet());

        List<ClientUserCsvPreviewRow> rows = new ArrayList<>();
        int readyRows = 0;
        for (int i = 1; i < csvRows.size(); i++) {
            List<String> csvRow = csvRows.get(i);
            if (isBlankRow(csvRow)) {
                continue;
            }
            ClientUserImportRow row = mapRow(i + 1, csvRow, columns);
            Validation validation = validateRow(row, organizationNames);
            if ("READY".equals(validation.status())) {
                readyRows++;
            }
            rows.add(new ClientUserCsvPreviewRow(
                    row.rowNumber(),
                    true,
                    row.username(),
                    row.firstname(),
                    row.lastname(),
                    row.email(),
                    row.phoneNumber(),
                    row.organization(),
                    row.moreInfo(),
                    validation.status(),
                    validation.message()
            ));
        }

        return new ClientUserCsvPreview(rows.size(), readyRows, List.copyOf(rows));
    }

    @Transactional
    public ClientUserImportResult importRows(ClientUserImportRequest request) {
        if (request == null || request.rows() == null || request.rows().isEmpty()) {
            throw new IllegalArgumentException("Нет строк для импорта");
        }

        Map<String, Organization> organizations = loadOrganizations();
        List<ClientUserImportCredential> credentials = new ArrayList<>();
        List<ClientCsvImportIssue> issues = new ArrayList<>();
        int totalRows = 0;
        int created = 0;
        int skipped = 0;

        for (ClientUserImportRow input : request.rows()) {
            if (input == null || Boolean.FALSE.equals(input.selected())) {
                continue;
            }
            totalRows++;
            int rowNumber = input.rowNumber() == null ? totalRows + 1 : input.rowNumber();
            ClientUserImportRow row = normalizeRow(input, rowNumber);

            try {
                Validation validation = validateRow(row, organizations.keySet());
                if ("ERROR".equals(validation.status())) {
                    skipped++;
                    issues.add(new ClientCsvImportIssue(rowNumber, "ERROR", validation.message()));
                    continue;
                }

                String username = normalizeUsername(row.username());
                if (userRepository.findFirstByUsernameIgnoreCase(username).isPresent()) {
                    skipped++;
                    issues.add(new ClientCsvImportIssue(rowNumber, "INFO", "Учётная запись " + username + " уже существует"));
                    continue;
                }

                Organization organization = resolveOrganization(row.organization(), organizations);
                String rawPassword = generatePassword(16);
                Client existingClient = findExistingClient(row, username).orElse(null);

                UserDto dto = new UserDto();
                dto.setUsername(username);
                dto.setFirstname(row.firstname());
                dto.setLastname(row.lastname());
                dto.setPassword(rawPassword);
                dto.setAuthorities(Role.CLIENT.getName());
                dto.setAvailableOrganizationIds(List.of());
                dto.setClientOrganizationId(organization.getId());
                dto.setClientId(existingClient == null ? null : existingClient.getId());
                dto.setCreateNewClientCard(existingClient == null);

                User savedUser = userService.newUser(dto);
                enrichClientCard(savedUser.getClientId(), row, username);
                created++;
                credentials.add(new ClientUserImportCredential(
                        username,
                        rawPassword,
                        row.firstname(),
                        row.lastname(),
                        organization.getName()
                ));
            } catch (Exception exception) {
                skipped++;
                issues.add(new ClientCsvImportIssue(rowNumber, "ERROR", safeMessage(exception)));
            }
        }

        return new ClientUserImportResult(totalRows, created, skipped, List.copyOf(credentials), List.copyOf(issues));
    }

    private Map<String, Organization> loadOrganizations() {
        Map<String, Organization> result = new LinkedHashMap<>();
        organizationService.getOrganizations().forEach(organization -> {
            if (organization.getName() != null && !organization.getName().isBlank()) {
                result.put(normalizeKey(organization.getName()), organization);
            }
        });
        return result;
    }

    private Organization resolveOrganization(
            String organizationName,
            Map<String, Organization> organizations
    ) {
        String normalizedName = clean(organizationName);
        if (normalizedName == null) {
            throw new IllegalArgumentException("Не указана организация");
        }
        Organization existing = organizations.get(normalizeKey(normalizedName));
        if (existing == null) {
            throw new IllegalArgumentException("Организация «" + normalizedName + "» не найдена");
        }
        return existing;
    }

    private Optional<Client> findExistingClient(ClientUserImportRow row, String username) {
        String email = clean(row.email());
        if (email != null) {
            Optional<Client> byEmail = clientRepository.findFirstByEmailIgnoreCase(email);
            if (byEmail.isPresent()) {
                return byEmail;
            }
        }
        return clientRepository.findFirstByEmailIgnoreCase(username);
    }

    private void enrichClientCard(Long clientId, ClientUserImportRow row, String username) {
        if (clientId == null) {
            return;
        }
        clientRepository.findById(clientId).ifPresent(client -> {
            boolean changed = false;
            String email = clean(row.email());
            if (email == null) {
                email = username;
            }
            if (email != null && !Objects.equals(email, client.getEmail())) {
                client.setEmail(email);
                changed = true;
            }
            if (clean(row.phoneNumber()) != null) {
                client.setPhoneNumber(clean(row.phoneNumber()));
                changed = true;
            }
            if (clean(row.moreInfo()) != null) {
                client.setMoreInfo(clean(row.moreInfo()));
                changed = true;
            }
            if (changed) {
                clientRepository.saveAndFlush(client);
            }
        });
    }

    private Validation validateRow(ClientUserImportRow input, Set<String> organizationNames) {
        ClientUserImportRow row = normalizeRow(input, input.rowNumber() == null ? 0 : input.rowNumber());
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (row.username() == null) {
            errors.add("не указан логин");
        } else if (!USERNAME_PATTERN.matcher(row.username()).matches()) {
            errors.add("логин должен быть в формате name@domain.zone; для AD-логинов используйте массовое добавление домена");
        }
        if (row.firstname() == null) {
            errors.add("не указано имя");
        }
        if (row.lastname() == null) {
            errors.add("не указана фамилия");
        }
        if (row.organization() == null) {
            errors.add("не указана организация");
        } else if (!organizationNames.contains(normalizeKey(row.organization()))) {
            errors.add("организация не найдена");
        }
        if (row.username() != null && USERNAME_PATTERN.matcher(row.username()).matches()
                && userRepository.findFirstByUsernameIgnoreCase(row.username()).isPresent()) {
            warnings.add("учётная запись уже существует и будет пропущена");
        }

        if (!errors.isEmpty()) {
            return new Validation("ERROR", String.join("; ", errors));
        }
        if (!warnings.isEmpty()) {
            return new Validation("WARNING", String.join("; ", warnings));
        }
        return new Validation("READY", "Готово к импорту");
    }

    private ClientUserImportRow mapRow(int rowNumber, List<String> row, Map<String, Integer> columns) {
        String email = normalizeEmail(read(row, columns, "email", "mail", "emailaddress", "e-mail"));
        String username = read(row, columns,
                "username", "login", "samaccountname", "userprincipalname", "логин", "учетнаязапись", "учётнаязапись");
        if (clean(username) == null) {
            username = email;
        }
        return normalizeRow(new ClientUserImportRow(
                rowNumber,
                true,
                username,
                read(row, columns, "firstname", "givenname", "first_name", "имя"),
                read(row, columns, "lastname", "surname", "sn", "last_name", "фамилия"),
                email,
                read(row, columns, "phonenumber", "phone", "telephonenumber", "mobile", "mobilephone", "телефон"),
                read(row, columns, "organization", "company", "department", "организация", "компания", "отдел"),
                read(row, columns, "moreinfo", "description", "title", "info", "информация", "описание", "должность")
        ), rowNumber);
    }

    private ClientUserImportRow normalizeRow(ClientUserImportRow row, int rowNumber) {
        String email = normalizeEmail(row == null ? null : row.email());
        String username = clean(row == null ? null : row.username());
        if (username == null) {
            username = email;
        }
        username = username == null ? null : normalizeUsername(username);
        return new ClientUserImportRow(
                rowNumber,
                row == null ? true : row.selected(),
                username,
                clean(row == null ? null : row.firstname()),
                clean(row == null ? null : row.lastname()),
                email,
                clean(row == null ? null : row.phoneNumber()),
                clean(row == null ? null : row.organization()),
                clean(row == null ? null : row.moreInfo())
        );
    }

    private String generatePassword(int length) {
        int targetLength = Math.max(length, 12);
        List<Character> characters = new ArrayList<>();
        characters.add(randomChar(PASSWORD_LOWER));
        characters.add(randomChar(PASSWORD_UPPER));
        characters.add(randomChar(PASSWORD_DIGITS));
        characters.add(randomChar(PASSWORD_SPECIAL));
        while (characters.size() < targetLength) {
            characters.add(randomChar(PASSWORD_ALL));
        }
        for (int i = characters.size() - 1; i > 0; i--) {
            int j = secureRandom.nextInt(i + 1);
            Character tmp = characters.get(i);
            characters.set(i, characters.get(j));
            characters.set(j, tmp);
        }
        StringBuilder result = new StringBuilder(targetLength);
        characters.forEach(result::append);
        return result.toString();
    }

    private char randomChar(String source) {
        return source.charAt(secureRandom.nextInt(source.length()));
    }

    private String normalizeUsername(String value) {
        String normalized = clean(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeEmail(String value) {
        String normalized = clean(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeKey(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private String clean(String value) {
        String normalized = Objects.toString(value, "").trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String safeMessage(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        return message == null || message.isBlank() ? "Не удалось импортировать строку" : message;
    }

    private void validateHeader(Map<String, Integer> columns) {
        boolean hasIdentity = hasAny(columns,
                "username", "login", "samaccountname", "userprincipalname", "логин",
                "email", "mail", "emailaddress", "e-mail");
        if (!hasIdentity) {
            throw new IllegalArgumentException(
                    "Не найдена колонка логина. Поддерживаются username, sAMAccountName, UserPrincipalName, email или mail"
            );
        }
    }

    private boolean hasAny(Map<String, Integer> columns, String... aliases) {
        for (String alias : aliases) {
            if (columns.containsKey(normalizeHeader(alias))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Integer> indexColumns(List<String> header) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String value = header.get(i);
            if (i == 0 && value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
                value = value.substring(1);
            }
            String normalized = normalizeHeader(value);
            if (!normalized.isBlank()) {
                result.putIfAbsent(normalized, i);
            }
        }
        return result;
    }

    private String normalizeHeader(String value) {
        return Objects.toString(value, "")
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String read(List<String> row, Map<String, Integer> columns, String... aliases) {
        for (String alias : aliases) {
            Integer index = columns.get(normalizeHeader(alias));
            if (index == null || index >= row.size()) {
                continue;
            }
            String value = clean(row.get(index));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlankRow(List<String> row) {
        return row == null || row.stream().allMatch(value -> clean(value) == null);
    }

    private List<List<String>> parseCsv(MultipartFile file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder contentBuilder = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                contentBuilder.append(buffer, 0, read);
            }
            String content = contentBuilder.toString();
            if (content.isBlank()) {
                return List.of();
            }
            return parseCsvContent(content, detectDelimiter(content));
        }
    }

    private char detectDelimiter(String content) {
        String firstRecord = firstLogicalRecord(content);
        int commas = countDelimiter(firstRecord, ',');
        int semicolons = countDelimiter(firstRecord, ';');
        return semicolons > commas ? ';' : ',';
    }

    private String firstLogicalRecord(String content) {
        StringBuilder record = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    record.append(c).append(c);
                    i++;
                    continue;
                }
                quoted = !quoted;
            }
            if ((c == '\n' || c == '\r') && !quoted) {
                break;
            }
            record.append(c);
        }
        return record.toString();
    }

    private int countDelimiter(String record, char delimiter) {
        int count = 0;
        boolean quoted = false;
        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < record.length() && record.charAt(i + 1) == '"') {
                    i++;
                    continue;
                }
                quoted = !quoted;
            } else if (c == delimiter && !quoted) {
                count++;
            }
        }
        return count;
    }

    private List<List<String>> parseCsvContent(String content, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
                continue;
            }
            if (c == delimiter && !quoted) {
                row.add(field.toString());
                field.setLength(0);
                continue;
            }
            if ((c == '\n' || c == '\r') && !quoted) {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                continue;
            }
            field.append(c);
        }
        row.add(field.toString());
        if (!isBlankRow(row) || rows.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    private record Validation(String status, String message) {
    }
}
