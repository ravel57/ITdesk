package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Priority;
import ru.ravel.ItDesk.model.Status;
import ru.ravel.ItDesk.model.Tag;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.TaskType;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.ClientRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class ExportService {

	private static final String DEFAULT_FILENAME = "tasks_report.xlsx";
	private static final int MAX_COLUMN_WIDTH = 70 * 256;

	private final ClientRepository clientRepository;
	private final Logger logger = LoggerFactory.getLogger(this.getClass());


	public ResponseEntity<byte[]> exportToExcel() {
		return exportToExcel(Map.of());
	}


	@Transactional(readOnly = true)
	public ResponseEntity<byte[]> exportToExcel(Map<String, String> params) {
		ExportFilters filters = ExportFilters.from(params);
		List<ExportRow> rows = loadRows(filters);
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Заявки");
			Styles styles = createStyles(workbook);
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
			String[] columns = {
					"№",
					"ID заявки",
					"Дата создания",
					"Дата последней активности",
					"Дата закрытия",
					"Причина закрытия",
					"Статус",
					"Приоритет",
					"Тип",
					"Закрыта",
					"Дедлайн",
					"SLA старт",
					"Пользователь",
					"Организация",
					"Исполнитель",
					"Теги",
					"Название",
					"Описание"
			};

			int rowNum = 0;
			rowNum = createReportHeader(sheet, rowNum, filters, rows.size(), styles, columns.length);
			rowNum++;
			int tableHeaderRow = rowNum;
			Row headerRow = sheet.createRow(rowNum++);
			for (int i = 0; i < columns.length; i++) {
				Cell cell = headerRow.createCell(i);
				cell.setCellValue(columns[i]);
				cell.setCellStyle(styles.tableHeaderStyle());
			}
			int number = 1;
			for (ExportRow exportRow : rows) {
				Client client = exportRow.client();
				Task task = exportRow.task();
				Row row = sheet.createRow(rowNum++);
				int col = 0;
				writeCell(row, col++, number++, styles.cellStyle());
				writeCell(row, col++, task.getId(), styles.cellStyle());
				writeCell(row, col++, formatDate(task.getCreatedAt(), formatter), styles.cellStyle());
				writeCell(row, col++, formatDate(task.getLastActivity(), formatter), styles.cellStyle());
				writeCell(row, col++, formatDate(task.getClosedAt(), formatter), styles.cellStyle());
				writeCell(row, col++, getCloseReason(task), styles.wrapCellStyle());
				writeCell(row, col++, getStatusName(task.getStatus()), styles.cellStyle());
				writeCell(row, col++, getPriorityName(task.getPriority()), styles.cellStyle());
				writeCell(row, col++, getTaskTypeName(task.getType()), styles.cellStyle());
				writeCell(row, col++, Boolean.TRUE.equals(task.getCompleted()) ? "Да" : "Нет", styles.cellStyle());
				writeCell(row, col++, formatDate(task.getDeadline(), formatter), styles.cellStyle());
				writeCell(row, col++, task.getSla() == null ? "" : formatDate(task.getSla().getStartDate(), formatter), styles.cellStyle());
				writeCell(row, col++, getClientName(client), styles.cellStyle());
				writeCell(row, col++, client.getOrganization() == null ? "" : Objects.toString(client.getOrganization().getName(), ""), styles.cellStyle());
				writeCell(row, col++, getUserDisplayName(task.getExecutor()), styles.cellStyle());
				writeCell(row, col++, getTags(task), styles.cellStyle());
				writeCell(row, col++, Objects.toString(task.getName(), ""), styles.cellStyle());
				writeCell(row, col, Objects.toString(task.getDescription(), ""), styles.wrapCellStyle());
			}
			if (rowNum > tableHeaderRow + 1) {
				sheet.setAutoFilter(new CellRangeAddress(tableHeaderRow, rowNum - 1, 0, columns.length - 1));
			}
			sheet.createFreezePane(0, tableHeaderRow + 1);
			autoSizeColumns(sheet, columns.length);
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			workbook.write(outputStream);
			byte[] excelBytes = outputStream.toByteArray();
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
			headers.setContentDispositionFormData("attachment", DEFAULT_FILENAME);
			headers.setContentLength(excelBytes.length);
			return new ResponseEntity<>(excelBytes, headers, HttpStatus.OK);
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
			return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}


	private List<ExportRow> loadRows(ExportFilters filters) {
		List<ExportRow> rows = new ArrayList<>();
		for (Client client : clientRepository.findAll()) {
			List<Task> tasks = Objects.requireNonNullElse(client.getTasks(), List.<Task>of()).stream()
					.sorted(Comparator.comparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
					.toList();
			for (Task task : tasks) {
				if (matches(client, task, filters)) {
					rows.add(new ExportRow(client, task));
				}
			}
		}
		return rows;
	}


	private boolean matches(Client client, Task task, ExportFilters filters) {
		if (task == null) {
			return false;
		}
		if (!between(task.getCreatedAt(), filters.createdFrom(), filters.createdTo())) {
			return false;
		}
		if (!between(task.getLastActivity(), filters.updatedFrom(), filters.updatedTo())) {
			return false;
		}
		if (!between(task.getClosedAt(), filters.closedFrom(), filters.closedTo())) {
			return false;
		}
		if (filters.completed() != null && !Objects.equals(Boolean.TRUE.equals(task.getCompleted()), filters.completed())) {
			return false;
		}
		if (!filters.statusIds().isEmpty() && (task.getStatus() == null || !filters.statusIds().contains(task.getStatus().getId()))) {
			return false;
		}
		if (!filters.priorityIds().isEmpty() && (task.getPriority() == null || !filters.priorityIds().contains(task.getPriority().getId()))) {
			return false;
		}
		if (!filters.executorIds().isEmpty() && (task.getExecutor() == null || !filters.executorIds().contains(task.getExecutor().getId()))) {
			return false;
		}
		if (!filters.organizationIds().isEmpty() && (client == null || client.getOrganization() == null || !filters.organizationIds().contains(client.getOrganization().getId()))) {
			return false;
		}
		if (!filters.typeIds().isEmpty() && (task.getType() == null || !filters.typeIds().contains(task.getType().getId()))) {
			return false;
		}
		if (!filters.tagIds().isEmpty() && !hasAnyTag(task, filters.tagIds())) {
			return false;
		}
		return true;
	}

	private boolean hasAnyTag(Task task, Set<Long> tagIds) {
		return Objects.requireNonNullElse(task.getTags(), List.<Tag>of()).stream()
				.filter(Objects::nonNull)
				.map(Tag::getId)
				.anyMatch(tagIds::contains);
	}


	private boolean between(ZonedDateTime value, ZonedDateTime from, ZonedDateTime to) {
		if (from != null && (value == null || value.isBefore(from))) {
			return false;
		}
		if (to != null && (value == null || value.isAfter(to))) {
			return false;
		}
		return true;
	}


	private int createReportHeader(Sheet sheet, int rowNum, ExportFilters filters, int rowsCount, Styles styles, int columnsCount) {
		int lastColumn = Math.max(1, columnsCount - 1);
		Row titleRow = sheet.createRow(rowNum++);
		Cell titleCell = titleRow.createCell(0);
		titleCell.setCellValue("Отчет по заявкам");
		titleCell.setCellStyle(styles.titleStyle());
		sheet.addMergedRegion(new CellRangeAddress(titleRow.getRowNum(), titleRow.getRowNum(), 0, 16));
		Row generatedRow = sheet.createRow(rowNum++);
		writeKeyValue(generatedRow, "Сформировано", ZonedDateTime.now(filters.zoneId()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")), styles);

		Row countRow = sheet.createRow(rowNum++);
		writeKeyValue(countRow, "Количество строк", String.valueOf(rowsCount), styles);

		Row filtersTitleRow = sheet.createRow(rowNum++);
		Cell filtersTitleCell = filtersTitleRow.createCell(0);
		filtersTitleCell.setCellValue("Примененные фильтры");
		filtersTitleCell.setCellStyle(styles.sectionStyle());
		sheet.addMergedRegion(new CellRangeAddress(filtersTitleRow.getRowNum(), filtersTitleRow.getRowNum(), 0, lastColumn));

		Map<String, String> filterLabels = filters.toDisplayMap();
		for (Map.Entry<String, String> entry : filterLabels.entrySet()) {
			Row row = sheet.createRow(rowNum++);
			writeKeyValue(row, entry.getKey(), entry.getValue(), styles);
		}

		return rowNum;
	}


	private void writeKeyValue(Row row, String key, String value, Styles styles) {
		Cell keyCell = row.createCell(0);
		keyCell.setCellValue(key);
		keyCell.setCellStyle(styles.keyStyle());

		Cell valueCell = row.createCell(1);
		valueCell.setCellValue(value == null || value.isBlank() ? "—" : value);
		valueCell.setCellStyle(styles.cellStyle());
	}


	private Styles createStyles(Workbook workbook) {
		Font titleFont = workbook.createFont();
		titleFont.setBold(true);
		titleFont.setFontHeightInPoints((short) 16);

		CellStyle titleStyle = workbook.createCellStyle();
		titleStyle.setFont(titleFont);
		titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

		Font sectionFont = workbook.createFont();
		sectionFont.setBold(true);
		sectionFont.setFontHeightInPoints((short) 12);

		CellStyle sectionStyle = workbook.createCellStyle();
		sectionStyle.setFont(sectionFont);
		sectionStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
		sectionStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

		Font headerFont = workbook.createFont();
		headerFont.setBold(true);

		CellStyle tableHeaderStyle = workbook.createCellStyle();
		tableHeaderStyle.setFont(headerFont);
		tableHeaderStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
		tableHeaderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		tableHeaderStyle.setAlignment(HorizontalAlignment.CENTER);
		tableHeaderStyle.setVerticalAlignment(VerticalAlignment.CENTER);
		tableHeaderStyle.setWrapText(true);

		CellStyle keyStyle = workbook.createCellStyle();
		keyStyle.setFont(headerFont);

		CellStyle cellStyle = workbook.createCellStyle();
		cellStyle.setVerticalAlignment(VerticalAlignment.TOP);

		CellStyle wrapCellStyle = workbook.createCellStyle();
		wrapCellStyle.setVerticalAlignment(VerticalAlignment.TOP);
		wrapCellStyle.setWrapText(true);

		return new Styles(titleStyle, sectionStyle, tableHeaderStyle, keyStyle, cellStyle, wrapCellStyle);
	}


	private void autoSizeColumns(Sheet sheet, int columnsCount) {
		for (int i = 0; i < columnsCount; i++) {
			sheet.autoSizeColumn(i);
			int width = sheet.getColumnWidth(i);
			sheet.setColumnWidth(i, Math.min(Math.max(width + 512, 10 * 256), MAX_COLUMN_WIDTH));
		}
	}


	private void writeCell(Row row, int col, Object value, CellStyle style) {
		Cell cell = row.createCell(col);
		if (value instanceof Number number) {
			cell.setCellValue(number.doubleValue());
		} else {
			cell.setCellValue(Objects.toString(value, ""));
		}
		cell.setCellStyle(style);
	}


	private String formatDate(ZonedDateTime value, DateTimeFormatter formatter) {
		return value == null ? "" : value.format(formatter);
	}


	private String getClientName(Client client) {
		if (client == null) {
			return "";
		}
		return "%s %s".formatted(
				Objects.toString(client.getFirstname(), ""),
				Objects.toString(client.getLastname(), "")
		).trim();
	}


	private String getUserDisplayName(User user) {
		if (user == null) {
			return "";
		}
		String fullName = "%s %s".formatted(
				Objects.toString(user.getLastname(), ""),
				Objects.toString(user.getFirstname(), "")
		).trim();
		return fullName.isBlank() ? Objects.toString(user.getUsername(), "") : fullName;
	}


	private String getStatusName(Status status) {
		return status == null ? "" : Objects.toString(status.getName(), "");
	}


	private String getPriorityName(Priority priority) {
		return priority == null ? "" : Objects.toString(priority.getName(), "");
	}


	private String getTaskTypeName(TaskType taskType) {
		return taskType == null ? "" : Objects.toString(taskType.getType(), "");
	}


	private String getTags(Task task) {
		return Objects.requireNonNullElse(task.getTags(), List.<Tag>of()).stream()
				.filter(Objects::nonNull)
				.map(Tag::getName)
				.filter(Objects::nonNull)
				.collect(Collectors.joining(", "));
	}


	private String getCloseReason(Task task) {
		if (task == null || !Boolean.TRUE.equals(task.getCompleted())) {
			return "";
		}
		return Objects.toString(task.getStatusChangeReason(), "").trim();
	}


	private record ExportRow(Client client, Task task) {
	}


	private record Styles(
			CellStyle titleStyle,
			CellStyle sectionStyle,
			CellStyle tableHeaderStyle,
			CellStyle keyStyle,
			CellStyle cellStyle,
			CellStyle wrapCellStyle
	) {
	}


	private record ExportFilters(
			ZonedDateTime createdFrom,
			ZonedDateTime createdTo,
			ZonedDateTime updatedFrom,
			ZonedDateTime updatedTo,
			ZonedDateTime closedFrom,
			ZonedDateTime closedTo,
			Set<Long> statusIds,
			Set<Long> priorityIds,
			Set<Long> executorIds,
			Set<Long> organizationIds,
			Set<Long> typeIds,
			Set<Long> tagIds,
			Boolean completed,
			ZoneId zoneId
	) {
		static ExportFilters from(Map<String, String> params) {
			Map<String, String> safe = params == null ? Map.of() : params;
			ZoneId zoneId = parseZone(safe.get("timezone"));
			return new ExportFilters(
					parseFrom(safe.get("createdFrom"), zoneId),
					parseTo(safe.get("createdTo"), zoneId),
					parseFrom(safe.get("updatedFrom"), zoneId),
					parseTo(safe.get("updatedTo"), zoneId),
					parseFrom(safe.get("closedFrom"), zoneId),
					parseTo(safe.get("closedTo"), zoneId),
					parseIds(safe.get("statusIds")),
					parseIds(safe.get("priorityIds")),
					parseIds(safe.get("executorIds")),
					parseIds(safe.get("organizationIds")),
					parseIds(safe.get("typeIds")),
					parseIds(safe.get("tagIds")),
					parseBooleanFilter(safe.get("completed")),
					zoneId
			);
		}

		Map<String, String> toDisplayMap() {
			Map<String, String> result = new LinkedHashMap<>();
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
			result.put("Часовой пояс", zoneId.getId());
			result.put("Создано с", formatFilterDate(createdFrom, formatter));
			result.put("Создано по", formatFilterDate(createdTo, formatter));
			result.put("Изменено с", formatFilterDate(updatedFrom, formatter));
			result.put("Изменено по", formatFilterDate(updatedTo, formatter));
			result.put("Закрыто с", formatFilterDate(closedFrom, formatter));
			result.put("Закрыто по", formatFilterDate(closedTo, formatter));
			result.put("Статусы ID", joinIds(statusIds));
			result.put("Приоритеты ID", joinIds(priorityIds));
			result.put("Исполнители ID", joinIds(executorIds));
			result.put("Организации ID", joinIds(organizationIds));
			result.put("Типы заявок ID", joinIds(typeIds));
			result.put("Теги ID", joinIds(tagIds));
			result.put("Закрыта", completed == null ? "Все" : completed ? "Да" : "Нет");
			return result;
		}

		private static ZoneId parseZone(String value) {
			if (value == null || value.isBlank()) {
				return ZoneId.systemDefault();
			}
			try {
				return ZoneId.of(value);
			} catch (Exception ignored) {
				return ZoneId.systemDefault();
			}
		}


		private static ZonedDateTime parseFrom(String value, ZoneId zoneId) {
			LocalDate date = parseDate(value);
			return date == null ? null : date.atStartOfDay(zoneId);
		}


		private static ZonedDateTime parseTo(String value, ZoneId zoneId) {
			LocalDate date = parseDate(value);
			return date == null ? null : date.atTime(23, 59, 59).atZone(zoneId);
		}


		private static LocalDate parseDate(String value) {
			if (value == null || value.isBlank()) {
				return null;
			}
			try {
				return LocalDate.parse(value);
			} catch (Exception ignored) {
			}
			try {
				return ZonedDateTime.parse(value).toLocalDate();
			} catch (Exception ignored) {
			}
			try {
				return OffsetDateTime.parse(value).toLocalDate();
			} catch (Exception ignored) {
			}
			try {
				return LocalDateTime.parse(value).toLocalDate();
			} catch (Exception ignored) {
			}
			return null;
		}


		private static Set<Long> parseIds(String value) {
			if (value == null || value.isBlank()) {
				return Set.of();
			}
			return List.of(value.split(",")).stream()
					.map(String::trim)
					.filter(item -> !item.isBlank())
					.map(item -> {
						try {
							return Long.parseLong(item);
						} catch (Exception ignored) {
							return null;
						}
					})
					.filter(Objects::nonNull)
					.collect(Collectors.toSet());
		}


		private static Boolean parseBooleanFilter(String value) {
			if (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) {
				return null;
			}
			return Boolean.parseBoolean(value);
		}


		private static String formatFilterDate(ZonedDateTime value, DateTimeFormatter formatter) {
			return value == null ? "—" : value.format(formatter);
		}


		private static String joinIds(Set<Long> ids) {
			if (ids == null || ids.isEmpty()) {
				return "Все";
			}
			return ids.stream()
					.sorted()
					.map(String::valueOf)
					.collect(Collectors.joining(", "));
		}
	}

}