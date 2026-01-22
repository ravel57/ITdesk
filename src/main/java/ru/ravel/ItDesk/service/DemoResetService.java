package ru.ravel.ItDesk.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "app", name = "is-demo", havingValue = "true")
@RequiredArgsConstructor
public class DemoResetService {

	private final JdbcTemplate jdbcTemplate;
	private final ReentrantLock lock = new ReentrantLock();

	private final UserRepository userRepository;
	private final ClientRepository clientRepository;
	private final OrganizationRepository organizationRepository;
	private final PriorityRepository priorityRepository;
	private final StatusRepository statusRepository;
	private final TaskRepository taskRepository;


	@Transactional
	public void truncateAllData() {
		lock.lock();
		try {
			// Собираем все таблицы public, кроме flyway_schema_history (и любых других исключений)
			List<String> tables = jdbcTemplate.queryForList("""
					select tablename
					from pg_tables
					where schemaname = 'public' and tablename not like 'spring_session%'
					""", String.class);
			if (tables.isEmpty()) {
				return;
			}
			String joined = tables.stream()
					.map("public.\"%s\""::formatted)
					.collect(Collectors.joining(", "));
			jdbcTemplate.execute("TRUNCATE TABLE %s RESTART IDENTITY CASCADE".formatted(joined));
		} finally {
			lock.unlock();
		}
	}


	@Transactional
	public void prepareTestData() {
		LicenseStarter.maxUsers = 1L;
		LicenseStarter.isLicenseActive = true;
		User user = User.builder()
				.username("demo@uldesk.ru")
				.password("$2a$12$zQndolZq/0kgjAmaCRCgb.pTSXTaj61kMVUZMac3xZ2JCLfC3zCke")
				.authorities(List.of(Role.ADMIN))
				.firstname("demo")
				.lastname("")
				.build();
		userRepository.save(user);
		userRepository.deleteById(1L);

		Organization organization1 = Organization.builder()
				.name("Организация 1")
				.build();
		Organization organization2 = Organization.builder()
				.name("Организация 2")
				.build();
		organizationRepository.saveAll(List.of(organization1, organization2));
		Priority defaultPriority = priorityRepository.findAll().stream()
				.filter(priority -> Boolean.TRUE.equals(priority.getDefaultSelection()))
				.findFirst()
				.orElseThrow();
		Status defaultStatus = statusRepository.findAll().stream()
				.filter(status -> Boolean.TRUE.equals(status.getDefaultSelection()))
				.findFirst()
				.orElseThrow();
		Task task1 = Task.builder()
				.name("Не работает почта")
				.priority(defaultPriority)
				.status(defaultStatus)
				.createdAt(ZonedDateTime.now())
				.description("")
				.executor(user)
				.build();
		Task task2 = Task.builder()
				.name("Не работает файловый сервер")
				.priority(defaultPriority)
				.status(CompletedStatus.getInstance())
				.completed(true)
				.createdAt(ZonedDateTime.now().minusDays(1))
				.description("")
				.build();
		Task task3 = Task.builder()
				.name("Заблокировалась учетная запись")
				.priority(defaultPriority)
				.status(defaultStatus)
				.createdAt(ZonedDateTime.now())
				.description("")
				.build();
		Task task4 = Task.builder()
				.name("Восстановить Word файл")
				.priority(defaultPriority)
				.status(defaultStatus)
				.createdAt(ZonedDateTime.now())
				.description("")
				.build();
		Task task5 = Task.builder()
				.name("Не работает удаленка")
				.priority(defaultPriority)
				.status(CompletedStatus.getInstance())
				.completed(true)
				.createdAt(ZonedDateTime.now().minusDays(2))
				.description("")
				.build();
		taskRepository.saveAll(List.of(task1, task2, task3, task4, task5));
		List<Client> clients = List.of(
				Client.builder()
						.firstname("Иван")
						.lastname("Иванов")
						.messages(List.of(
								Message.builder()
										.text("Добрый день! Не работает почта. Что делать?")
										.date(ZonedDateTime.now().minusMinutes(15))
										.isSent(false)
										.build(),
								Message.builder()
										.text("Добрый день! Сейчас посмотрим")
										.date(ZonedDateTime.now().minusMinutes(10))
										.isSent(true)
										.build(),
								Message.builder()
										.text("Починили, попробуйте сейчас.")
										.date(ZonedDateTime.now().minusMinutes(5))
										.isSent(true)
										.build(),
								Message.builder()
										.text("Заработала, спасибо!")
										.date(ZonedDateTime.now())
										.isSent(false)
										.build()
						))
						.messageFrom(MessageFrom.EMAIL)
						.organization(organization1)
						.tasks(List.of(task1, task2))
						.build(),
				Client.builder()
						.firstname("Петр")
						.lastname("Петров")
						.messages(List.of(
								Message.builder()
										.text("Здравствуйте! Не могу зайти в учетную запись, пишет что учетная запись заблокирована")
										.date(ZonedDateTime.now().minusMinutes(5))
										.isSent(false)
										.isComment(false)
										.isRead(false)
										.build(),
								Message.builder()
										.text("Добрый день! Сняли блокировку, попробуйте войти сейчас")
										.date(ZonedDateTime.now().minusMinutes(3))
										.isSent(true)
										.isComment(false)
										.isRead(false)
										.build()
						))
						.messageFrom(MessageFrom.TELEGRAM)
						.organization(organization2)
						.tasks(List.of(task3))
						.build(),
				Client.builder()
						.firstname("Павел")
						.lastname("Сидоров")
						.messages(List.of(
								Message.builder()
										.text("Добрый день! Не могу восстановить Word документ.")
										.date(ZonedDateTime.now().minusMinutes(25))
										.isSent(false)
										.isComment(false)
										.isRead(false)
										.build(),
								Message.builder()
										.text("Добрый день! Сейчас подключимся к Вам, чтобы помочь")
										.date(ZonedDateTime.now().minusMinutes(20))
										.isSent(true)
										.isComment(false)
										.isRead(false)
										.build()
						))
						.messageFrom(MessageFrom.WHATSAPP)
						.tasks(List.of(task4, task5))
						.build(),
				Client.builder()
						.firstname("Анна")
						.lastname("Морозова")
						.messages(List.of(Message.builder()
								.text("Hello World!")
								.date(ZonedDateTime.now().minusMinutes(1))
								.isSent(false)
								.isComment(false)
								.isRead(false)
								.build()))
						.messageFrom(MessageFrom.TELEGRAM)
						.build()
		);
		clientRepository.saveAll(clients);
	}
}