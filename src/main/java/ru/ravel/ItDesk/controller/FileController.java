package ru.ravel.ItDesk.controller;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final MinioClient minioClient;
	@Value("${minio.bucket-name}")
	String bucketName;


	@GetMapping(value = "/jpeg/{uuid}", produces = MediaType.IMAGE_JPEG_VALUE)
	public ResponseEntity<Object> imageFile(@PathVariable String uuid) {
		byte[] fileBytes = getFileBytes(uuid);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.IMAGE_JPEG);
		return ResponseEntity.ok().headers(headers).body(fileBytes);
	}


	@GetMapping(value = "/documents/{uuid}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	public ResponseEntity<Object> documentFile(@PathVariable String uuid) {
		byte[] fileBytes = getFileBytes(uuid);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
		return ResponseEntity.ok().headers(headers).body(fileBytes);
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
		try {
			InputStream inputStream = minioClient.getObject(GetObjectArgs.builder().bucket(bucketName).object(uuid).build());
			byte[] imageBytes = inputStream.readAllBytes();
			inputStream.close();
			return imageBytes;
		} catch (Exception e) {
			logger.error(e.getMessage());
			return null;
		}
	}

}