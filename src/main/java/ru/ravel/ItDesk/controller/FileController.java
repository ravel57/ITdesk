package ru.ravel.ItDesk.controller;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.ravel.ItDesk.component.SlidingFileCache;
import ru.ravel.ItDesk.repository.MessageRepository;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.service.UserService;


@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

	private final MinioClient minioClient;
	private final ClientRepository clientRepository;
	private final UserService userService;
	private final SlidingFileCache slidingFileCache;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Value("${minio.bucket-name}")
	String bucketName;


	@PostMapping("/upload")
	public ResponseEntity<Object> uploadFiles(@RequestParam("files") List<MultipartFile> files) {
		List<String> uuids = new ArrayList<>();
		try {
			for (MultipartFile file : files) {
				String uuid = UUID.randomUUID().toString();
				minioClient.putObject(
						PutObjectArgs.builder()
								.bucket(bucketName)
								.object(uuid)
								.stream(file.getInputStream(), file.getSize(), -1)
								.contentType(file.getContentType())
								.build()
				);
				uuids.add(uuid);
			}
			return ResponseEntity.ok().body(uuids);
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Collections.singletonList(e.getMessage()));
		}
	}


	@GetMapping(value = "/images/{uuid}", produces = MediaType.IMAGE_JPEG_VALUE)
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> imageFile(@PathVariable String uuid) {
		resolveFileAccess(uuid);
		byte[] fileBytes = getCachedFileBytesOrThrow("images", uuid);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.IMAGE_JPEG);
		headers.setCacheControl(CacheControl.maxAge(365, java.util.concurrent.TimeUnit.DAYS).cachePrivate().getHeaderValue());
		return ResponseEntity.ok().headers(headers).body(fileBytes);
	}


	@GetMapping(value = "/documents/{uuid}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
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


	@GetMapping(value = "/videos/{uuid}", produces = "video/mp4")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> videoFile(@PathVariable String uuid) {
		resolveFileAccess(uuid);
		byte[] fileBytes = getCachedFileBytesOrThrow("videos", uuid);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(new MediaType("video", "mp4"));
		headers.setContentLength(fileBytes.length);
		headers.setCacheControl(CacheControl.noStore().getHeaderValue());

		return ResponseEntity.ok().headers(headers).body(fileBytes);
	}


	@GetMapping(value = "/audios/{uuid}", produces = "video/mp4")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
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
		List<ClientRepository.FileAccessRow> rows = new ArrayList<>();
		rows.addAll(clientRepository.findClientMessageFileAccessRowsByFileUuid(uuid));
		rows.addAll(clientRepository.findTaskMessageFileAccessRowsByFileUuid(uuid));
		if (rows.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Файл не найден");
		}
		ClientRepository.FileAccessRow allowedRow = rows.stream()
				.filter(Objects::nonNull)
				.filter(row -> row.getClient() != null)
				.filter(row -> row.getMessage() != null)
				.filter(row -> canCurrentUserAccessClient(row.getClient()))
				.findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Нет доступа к файлу"));
		return new FileAccessContext(allowedRow.getClient(), allowedRow.getMessage());
	}


	private boolean canCurrentUserAccessClient(Client client) {
		try {
			userService.assertCurrentUserCanAccessClient(client);
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
			return inputStream.readAllBytes();
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			return null;
		}
	}


	private byte[] getCachedFileBytes(String namespace, String uuid) {
		return slidingFileCache.getOrLoad(namespace, uuid, () -> getFileBytes(uuid));
	}

}