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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.ravel.ItDesk.repository.MessageRepository;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

	private final MinioClient minioClient;
	private final MessageRepository messageRepository;

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
	public ResponseEntity<Object> imageFile(@PathVariable String uuid) {
		byte[] fileBytes = getFileBytes(uuid);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.IMAGE_JPEG);
		return ResponseEntity.ok().headers(headers).body(fileBytes);
	}


	@GetMapping(value = "/documents/{uuid}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	public ResponseEntity<Object> documentFile(@PathVariable String uuid) {
		String filename = messageRepository.findByFileUuid(uuid).orElseThrow().getFileName();
		String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
		byte[] fileBytes = getFileBytes(uuid);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
		headers.setContentDisposition(ContentDisposition.builder("attachment").filename(encodedFilename).build());
		headers.setContentLength(fileBytes != null ? fileBytes.length : 0);
		return ResponseEntity.ok()
				.headers(headers)
				.body(fileBytes);
	}


	@GetMapping(value = "/videos/{uuid}", produces = "video/mp4")
	public ResponseEntity<Object> videoFile(@PathVariable String uuid) {
		byte[] fileBytes = getFileBytes(uuid);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(new MediaType("video", "mp4"));
		return ResponseEntity.ok().headers(headers).body(fileBytes);
	}


	@GetMapping(value = "/audios/{uuid}", produces = "video/mp4")
	public ResponseEntity<Object> audioFile(@PathVariable String uuid) {
		byte[] fileBytes = getFileBytes(uuid);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(new MediaType("video", "mp4"));
		return ResponseEntity.ok().headers(headers).body(fileBytes);
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

}