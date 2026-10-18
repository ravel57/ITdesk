package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.ravel.ItDesk.component.PendingFileUploadRegistry;
import ru.ravel.ItDesk.component.SlidingFileCache;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.service.UserService;


@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@Tag(name = "Файлы", description = "Загрузка и безопасная выдача файлов, связанных с сообщениями и заявками.")
public class FileController {

	private static final long MAX_MESSAGE_FILE_SIZE_BYTES = 10L * 1024L * 1024L;
	private static final long MAX_UPLOAD_REQUEST_BYTES = 50L * 1024L * 1024L;
	private static final int MAX_FILES_PER_REQUEST = 10;
	private static final long MAX_SERVED_FILE_SIZE_BYTES = 64L * 1024L * 1024L;
	private static final String MAX_MESSAGE_FILE_SIZE_LABEL = "10 МБ";

	private final MinioClient minioClient;
	private final ClientRepository clientRepository;
	private final UserService userService;
	private final SlidingFileCache slidingFileCache;
	private final PendingFileUploadRegistry pendingFileUploadRegistry;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Value("${minio.bucket-name}")
	String bucketName;


	@Operation(summary = "Загрузить файлы", description = "Загружает файлы сообщений в объектное хранилище с проверкой допустимого размера.")
	@PostMapping("/upload")
	@PreAuthorize("@access.can('FILE_UPLOAD')")
	public ResponseEntity<Object> uploadFiles(@RequestParam("files") List<MultipartFile> files) {
		if (files == null || files.isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("message", "Не переданы файлы"));
		}
		if (files.size() > MAX_FILES_PER_REQUEST) {
			return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of(
					"message", "Слишком много файлов в одном запросе",
					"maxFiles", MAX_FILES_PER_REQUEST
			));
		}
		long totalSize = 0L;
		for (MultipartFile file : files) {
			if (file == null || file.isEmpty()) {
				return ResponseEntity.badRequest().body(Map.of("message", "Пустой файл не поддерживается"));
			}
			if (file.getSize() > MAX_MESSAGE_FILE_SIZE_BYTES) {
				String fileName = file.getOriginalFilename();
				if (fileName == null || fileName.isBlank()) {
					fileName = "Файл";
				}
				return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of(
						"message", String.format("Файл «%s» весит больше %s", fileName, MAX_MESSAGE_FILE_SIZE_LABEL),
						"maxSizeBytes", MAX_MESSAGE_FILE_SIZE_BYTES
				));
			}
			if (file.getSize() > MAX_UPLOAD_REQUEST_BYTES - totalSize) {
				return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of(
						"message", "Суммарный размер файлов превышает 50 МБ",
						"maxTotalSizeBytes", MAX_UPLOAD_REQUEST_BYTES
				));
			}
			totalSize += file.getSize();
		}

		String username = userService.getCurrentUser().getUsername();
		List<String> uuids = new ArrayList<>();
		try {
			for (MultipartFile file : files) {
				String uuid = UUID.randomUUID().toString();
				try (InputStream inputStream = file.getInputStream()) {
					minioClient.putObject(
							PutObjectArgs.builder()
									.bucket(bucketName)
									.object(uuid)
									.stream(inputStream, file.getSize(), -1)
									.contentType(file.getContentType())
									.build()
					);
				}
				// Add before registering so a Redis failure still removes the
				// already-created object in the catch block.
				uuids.add(uuid);
				pendingFileUploadRegistry.register(uuid, username);
			}
			return ResponseEntity.ok().body(uuids);
		} catch (Exception e) {
			for (String uuid : uuids) {
				try {
					pendingFileUploadRegistry.consume(uuid);
				} catch (Exception registryCleanupException) {
					logger.warn("Failed to clean upload ownership metadata for {}", uuid, registryCleanupException);
				}
				try {
					minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(uuid).build());
				} catch (Exception cleanupException) {
					logger.warn("Failed to clean partially uploaded object {}", uuid, cleanupException);
				}
			}
			logger.warn("File upload failed", e);
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Collections.singletonList("Не удалось загрузить файлы"));
		}
	}


	@Operation(summary = "Получить изображение", description = "Возвращает изображение по UUID после проверки доступа текущего пользователя к связанному клиенту.")
	@GetMapping(value = "/images/{uuid}", produces = MediaType.IMAGE_JPEG_VALUE)
	@PreAuthorize("@access.can('FILE_VIEW')")
	public ResponseEntity<Object> imageFile(@PathVariable String uuid) {
		resolveFileAccess(uuid);
		byte[] fileBytes = getCachedFileBytesOrThrow("images", uuid);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.IMAGE_JPEG);
		headers.setCacheControl(CacheControl.maxAge(365, java.util.concurrent.TimeUnit.DAYS).cachePrivate().getHeaderValue());
		return ResponseEntity.ok().headers(headers).body(fileBytes);
	}


	@Operation(summary = "Скачать документ", description = "Возвращает документ по UUID как вложение после проверки доступа к связанному сообщению.")
	@GetMapping(value = "/documents/{uuid}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	@PreAuthorize("@access.can('FILE_VIEW')")
	public ResponseEntity<Object> documentFile(@PathVariable String uuid) {
		FileAccessContext fileAccess = resolveFileAccess(uuid);
		String filename = fileAccess.message().getFileName();
		if (filename == null || filename.isBlank()) {
			filename = uuid;
		}
		String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
		byte[] fileBytes = getCachedFileBytesOrThrow("documents", uuid);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
		headers.setContentDisposition(ContentDisposition.builder("attachment").filename(encodedFilename).build());
		headers.setContentLength(fileBytes.length);
		headers.setCacheControl(CacheControl.noStore().getHeaderValue());

		return ResponseEntity.ok().headers(headers).body(fileBytes);
	}


	@Operation(summary = "Получить видео", description = "Возвращает видеофайл по UUID после проверки доступа к связанному сообщению.")
	@GetMapping(value = "/videos/{uuid}", produces = "video/mp4")
	@PreAuthorize("@access.can('FILE_VIEW')")
	public ResponseEntity<Object> videoFile(@PathVariable String uuid) {
		resolveFileAccess(uuid);
		byte[] fileBytes = getCachedFileBytesOrThrow("videos", uuid);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(new MediaType("video", "mp4"));
		headers.setContentLength(fileBytes.length);
		headers.setCacheControl(CacheControl.noStore().getHeaderValue());

		return ResponseEntity.ok().headers(headers).body(fileBytes);
	}


	@Operation(summary = "Получить аудио", description = "Возвращает аудиофайл по UUID после проверки доступа к связанному сообщению.")
	@GetMapping(value = "/audios/{uuid}", produces = "video/mp4")
	@PreAuthorize("@access.can('FILE_VIEW')")
	public ResponseEntity<Object> audioFile(@PathVariable String uuid) {
		resolveFileAccess(uuid);
		byte[] fileBytes = getCachedFileBytesOrThrow("audios", uuid);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(new MediaType("video", "mp4"));
		headers.setContentLength(fileBytes.length);
		headers.setCacheControl(CacheControl.noStore().getHeaderValue());
		return ResponseEntity.ok().headers(headers).body(fileBytes);
	}


	private FileAccessContext resolveFileAccess(String uuid) {
		if (uuid == null || uuid.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный идентификатор файла");
		}
		List<ClientRepository.FileAccessRow> clientRows = clientRepository.findClientMessageFileAccessRowsByFileUuid(uuid);
		List<ClientRepository.TaskFileAccessRow> taskRows = clientRepository.findTaskMessageFileAccessRowsByFileUuid(uuid);
		if (clientRows.isEmpty() && taskRows.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Файл не найден");
		}
		User currentUser = userService.getCurrentUser();
		Optional<ClientRepository.FileAccessRow> allowedClientRow = clientRows.stream()
				.filter(row -> canUserAccessClientMessageFile(currentUser, row))
				.findFirst();
		ClientRepository.FileAccessRow allowedRow = allowedClientRow.or(() -> taskRows.stream()
						.filter(row -> canUserAccessTaskMessageFile(currentUser, row))
						.map(ClientRepository.FileAccessRow.class::cast)
						.findFirst())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Нет доступа к файлу"));
		return new FileAccessContext(allowedRow.getClient(), allowedRow.getMessage());
	}


	/**
	 * Файл из чата клиента: нужен доступ к клиенту. Комментарии (внутренние заметки поддержки)
	 * не отдаются ни клиенту портала, ни менеджеру организации (OBSERVER); удалённые сообщения не отдаются никому.
	 */
	private boolean canUserAccessClientMessageFile(User user, ClientRepository.FileAccessRow row) {
		if (row == null || row.getClient() == null || row.getMessage() == null) {
			return false;
		}
		Message message = row.getMessage();
		if (Boolean.TRUE.equals(message.getDeleted())) {
			return false;
		}
		if (Boolean.TRUE.equals(message.getIsComment()) && (userService.isClient(user) || userService.isObserver(user))) {
			return false;
		}
		return canUserAccessClient(user, row.getClient());
	}


	/**
	 * Файл из внутреннего чата заявки: только поддержка, и с учётом видимости линии поддержки для оператора.
	 */
	private boolean canUserAccessTaskMessageFile(User user, ClientRepository.TaskFileAccessRow row) {
		if (row == null || row.getClient() == null || row.getMessage() == null || row.getTask() == null) {
			return false;
		}
		if (Boolean.TRUE.equals(row.getMessage().getDeleted())) {
			return false;
		}
		if (!userService.canUserSeeTaskChat(user)) {
			return false;
		}
		return userService.canUserAccessTask(user, row.getClient(), row.getTask());
	}


	private boolean canUserAccessClient(User user, Client client) {
		try {
			userService.assertUserCanAccessClient(user, client);
			return true;
		} catch (AccessDeniedException e) {
			return false;
		}
	}


	private record FileAccessContext(Client client, Message message) {
	}


	private byte[] getCachedFileBytesOrThrow(String namespace, String uuid) {
		byte[] fileBytes = getCachedFileBytes(namespace, uuid);
		if (fileBytes == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Файл не найден в хранилище");
		}
		return fileBytes;
	}


	@Nullable
	private byte[] getFileBytes(String uuid) {
		try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder().bucket(bucketName).object(uuid).build())) {
			byte[] bytes = inputStream.readNBytes((int) MAX_SERVED_FILE_SIZE_BYTES + 1);
			if (bytes.length > MAX_SERVED_FILE_SIZE_BYTES) {
				logger.warn("Refusing to buffer oversized object {}", uuid);
				return null;
			}
			return bytes;
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			return null;
		}
	}


	private byte[] getCachedFileBytes(String namespace, String uuid) {
		return slidingFileCache.getOrLoad(namespace, uuid, () -> getFileBytes(uuid));
	}

}