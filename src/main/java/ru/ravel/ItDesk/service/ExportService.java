package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.repository.ClientRepository;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ExportService {

	private final ClientRepository clientRepository;


	private final Logger logger = LoggerFactory.getLogger(this.getClass());


	public ResponseEntity<byte[]> exportToExcel() {
		List<Client> clients = clientRepository.findAll();
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("Data");
			Row headerRow = sheet.createRow(0);
			headerRow.createCell(0).setCellValue("ID Заявки");
			headerRow.createCell(1).setCellValue("Дата создания");
			headerRow.createCell(2).setCellValue("Статус");
			headerRow.createCell(3).setCellValue("Пользователь");
			headerRow.createCell(4).setCellValue("Организация");
			headerRow.createCell(5).setCellValue("Название");
			headerRow.createCell(6).setCellValue("Описание");
			headerRow.createCell(7).setCellValue("Дата последней активности");
			headerRow.createCell(8).setCellValue("Исполнитель");
			int rowNum = 1;
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
			for (Client client : clients) {
				List<Task> tasks = client.getTasks();
				tasks.sort(Comparator.comparing(Task::getCreatedAt));
				for (Task task : tasks) {
					Row row = sheet.createRow(rowNum++);
					row.createCell(0).setCellValue(task.getId());
					row.createCell(1).setCellValue(task.getCreatedAt().format(formatter));
					row.createCell(2).setCellValue(task.getStatus().getName());
					row.createCell(3).setCellValue("%s %s".formatted(client.getFirstname(), Objects.requireNonNullElse(client.getLastname(), "")));
					if (client.getOrganization() != null) {
						row.createCell(4).setCellValue(client.getOrganization().getName());
					}
					row.createCell(5).setCellValue(task.getName());
					row.createCell(6).setCellValue(task.getDescription());
					row.createCell(7).setCellValue(task.getLastActivity().format(formatter));
					if (task.getExecutor() != null) {
						row.createCell(8).setCellValue("%s %s".formatted(task.getExecutor().getFirstname(), task.getExecutor().getLastname()));
					}
				}
			}
			byte[] excelBytes;
			try (FileOutputStream fileOut = new FileOutputStream("hibernate_data.xlsx")) {
				workbook.write(fileOut);
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				workbook.write(outputStream);
				excelBytes = outputStream.toByteArray();
			}
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
			headers.setContentDispositionFormData("attachment", "data.xlsx");
			headers.setContentLength(excelBytes.length);
			return new ResponseEntity<>(excelBytes, headers, HttpStatus.OK);
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
			return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

}
