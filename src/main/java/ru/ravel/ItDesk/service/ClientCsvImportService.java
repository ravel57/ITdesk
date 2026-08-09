package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.ravel.ItDesk.dto.ClientCsvImportIssue;
import ru.ravel.ItDesk.dto.ClientCsvImportResult;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.ClientRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ClientCsvImportService {

	private static final String CSV_TEMPLATE = "firstname,lastname,email,phoneNumber,organization,moreInfo\r\n";

	private final ClientRepository clientRepository;
	private final OrganizationService organizationService;
	private final GlobalSearchService globalSearchService;
	private final EventPublisher eventPublisher;

	public ClientCsvImportResult importCsv(
			MultipartFile file,
			boolean updateExisting
	) throws IOException {
		if (file == null || file.isEmpty()) {
			throw new IllegalArgumentException("CSV-файл пуст или не передан");
		}

		List<List<String>> rows = parseCsv(file);
		if (rows.isEmpty()) {
			throw new IllegalArgumentException("CSV-файл не содержит строк");
		}

		List<String> header = rows.getFirst();
		Map<String, Integer> columns = indexColumns(header);
		validateHeader(columns);

		Map<String, Organization> organizations = new HashMap<>();
		organizationService.getOrganizations().forEach(organization -> {
			if (organization.getName() != null && !organization.getName().isBlank()) {
				organizations.put(normalizeValue(organization.getName()), organization);
			}
		});

		int totalRows = 0;
		int created = 0;
		int updated = 0;
		int skipped = 0;
		List<ClientCsvImportIssue> issues = new ArrayList<>();

		for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
			int csvRowNumber = rowIndex + 1;
			List<String> row = rows.get(rowIndex);
			if (isBlankRow(row)) {
				continue;
			}
			totalRows++;

			try {
				ImportedClient imported = mapRow(row, columns);
				if (!imported.hasIdentity()) {
					skipped++;
					issues.add(new ClientCsvImportIssue(
							csvRowNumber,
							"ERROR",
							"Строка пропущена: укажите хотя бы имя, фамилию, email или телефон"
					));
					continue;
				}

				Organization organization = resolveOrganization(
						imported.organization(),
						organizations,
						csvRowNumber,
						issues
				);

				Optional<Client> existing = imported.email() == null
						? Optional.empty()
						: clientRepository.findFirstByEmailIgnoreCase(imported.email());

				if (existing.isPresent()) {
					if (!updateExisting) {
						skipped++;
						issues.add(new ClientCsvImportIssue(
								csvRowNumber,
								"INFO",
								"Клиент с email " + imported.email() + " уже существует"
						));
						continue;
					}

					Client client = existing.get();
					mergeClient(client, imported, organization);
					Client saved = clientRepository.saveAndFlush(client);
					updated++;
					publishSavedClient(saved, TriggerType.CLIENT_UPDATED, csvRowNumber, issues);
					continue;
				}

				Client client = Client.builder()
						.firstname(imported.firstname())
						.lastname(imported.lastname())
						.email(imported.email())
						.phoneNumber(imported.phoneNumber())
						.organization(organization)
						.moreInfo(imported.moreInfo())
						.build();

				Client saved = clientRepository.saveAndFlush(client);
				created++;
				publishSavedClient(saved, TriggerType.CLIENT_CREATED, csvRowNumber, issues);
			} catch (Exception exception) {
				skipped++;
				issues.add(new ClientCsvImportIssue(
						csvRowNumber,
						"ERROR",
						"Не удалось импортировать строку: " + safeMessage(exception)
				));
			}
		}

		return new ClientCsvImportResult(totalRows, created, updated, skipped, List.copyOf(issues));
	}

	public byte[] getTemplate() {
		return CSV_TEMPLATE.getBytes(StandardCharsets.UTF_8);
	}

	private void publishSavedClient(
			Client client,
			TriggerType triggerType,
			int rowNumber,
			List<ClientCsvImportIssue> issues
	) {
		try {
			globalSearchService.indexClient(client);
		} catch (Exception exception) {
			issues.add(new ClientCsvImportIssue(
					rowNumber,
					"WARNING",
					"Клиент сохранён, но не удалось обновить поисковый индекс: " + safeMessage(exception)
			));
		}
		try {
			eventPublisher.publish(triggerType, Map.of("client", client));
		} catch (Exception exception) {
			issues.add(new ClientCsvImportIssue(
					rowNumber,
					"WARNING",
					"Клиент сохранён, но не удалось создать событие автоматизации: " + safeMessage(exception)
			));
		}
	}

	private Organization resolveOrganization(
			String organizationName,
			Map<String, Organization> organizations,
			int rowNumber,
			List<ClientCsvImportIssue> issues
	) {
		if (organizationName == null) {
			return null;
		}

		Organization organization = organizations.get(normalizeValue(organizationName));
		if (organization != null) {
			return organization;
		}

		issues.add(new ClientCsvImportIssue(
				rowNumber,
				"WARNING",
				"Организация \"" + organizationName + "\" не найдена; клиент будет импортирован без организации"
		));
		return null;
	}

	private void mergeClient(Client client, ImportedClient imported, Organization organization) {
		if (imported.firstname() != null) {
			client.setFirstname(imported.firstname());
		}
		if (imported.lastname() != null) {
			client.setLastname(imported.lastname());
		}
		if (imported.email() != null) {
			client.setEmail(imported.email());
		}
		if (imported.phoneNumber() != null) {
			client.setPhoneNumber(imported.phoneNumber());
		}
		if (imported.organization() != null && organization != null) {
			client.setOrganization(organization);
		}
		if (imported.moreInfo() != null) {
			client.setMoreInfo(imported.moreInfo());
		}
	}

	private ImportedClient mapRow(List<String> row, Map<String, Integer> columns) {
		return new ImportedClient(
				read(row, columns, "firstname", "givenname", "first_name", "имя"),
				read(row, columns, "lastname", "surname", "sn", "last_name", "фамилия"),
				normalizeEmail(read(row, columns, "email", "mail", "emailaddress", "userprincipalname", "e-mail")),
				read(row, columns, "phonenumber", "phone", "telephonenumber", "mobile", "mobilephone", "телефон"),
				read(row, columns, "organization", "company", "department", "организация", "компания", "отдел"),
				read(row, columns, "moreinfo", "description", "title", "info", "информация", "описание", "должность")
		);
	}

	private void validateHeader(Map<String, Integer> columns) {
		boolean hasSupportedColumn = StreamSupport.hasAny(
				columns,
				"firstname", "givenname", "first_name", "имя",
				"lastname", "surname", "sn", "last_name", "фамилия",
				"email", "mail", "emailaddress", "userprincipalname", "e-mail",
				"phonenumber", "phone", "telephonenumber", "mobile", "mobilephone", "телефон"
		);
		if (!hasSupportedColumn) {
			throw new IllegalArgumentException(
					"Не найдены поддерживаемые колонки. Используйте шаблон: firstname,lastname,email,phoneNumber,organization,moreInfo"
			);
		}
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
			char delimiter = detectDelimiter(content);
			return parseCsvContent(content, delimiter);
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

		if (quoted) {
			throw new IllegalArgumentException("Некорректный CSV: незакрытое поле в кавычках");
		}
		if (field.length() > 0 || !row.isEmpty()) {
			row.add(field.toString());
			rows.add(row);
		}

		while (!rows.isEmpty() && isBlankRow(rows.getLast())) {
			rows.removeLast();
		}
		return rows;
	}

	private boolean isBlankRow(List<String> row) {
		return row == null || row.stream().allMatch(value -> value == null || value.isBlank());
	}

	private String normalizeHeader(String value) {
		if (value == null) {
			return "";
		}
		return value.trim()
				.toLowerCase(Locale.ROOT)
				.replace(" ", "")
				.replace("_", "")
				.replace("-", "");
	}

	private String normalizeValue(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private String normalizeEmail(String value) {
		String email = clean(value);
		return email == null ? null : email.toLowerCase(Locale.ROOT);
	}

	private String clean(String value) {
		if (value == null) {
			return null;
		}
		String cleaned = value.trim();
		return cleaned.isEmpty() ? null : cleaned;
	}

	private String safeMessage(Exception exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
	}

	private record ImportedClient(
			String firstname,
			String lastname,
			String email,
			String phoneNumber,
			String organization,
			String moreInfo
	) {
		private boolean hasIdentity() {
			return firstname != null || lastname != null || email != null || phoneNumber != null;
		}
	}

	private static final class StreamSupport {
		private StreamSupport() {
		}

		private static boolean hasAny(Map<String, Integer> columns, String... aliases) {
			for (String alias : aliases) {
				String normalized = alias.trim()
						.toLowerCase(Locale.ROOT)
						.replace(" ", "")
						.replace("_", "")
						.replace("-", "");
				if (columns.containsKey(normalized)) {
					return true;
				}
			}
			return false;
		}
	}
}
