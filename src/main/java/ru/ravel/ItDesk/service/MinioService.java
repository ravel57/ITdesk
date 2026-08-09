package ru.ravel.ItDesk.service;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@RequiredArgsConstructor
public class MinioService {

	private final MinioClient minioClient;

	@Value("${minio.bucket-name}")
	private String bucketName;


	public File getFile(String fileName, String fileUuid) {
		Path tempFile = null;
		try {
			String suffix = safeSuffix(fileName);
			tempFile = Files.createTempFile("uldesk-attachment-", suffix);
			try (GetObjectResponse response = minioClient.getObject(
					GetObjectArgs.builder()
							.bucket(bucketName)
							.object(fileUuid)
							.build())) {
				Files.copy(response, tempFile, StandardCopyOption.REPLACE_EXISTING);
			}
			return tempFile.toFile();
		} catch (Exception e) {
			if (tempFile != null) {
				try {
					Files.deleteIfExists(tempFile);
				} catch (Exception ignored) {
					// best effort cleanup
				}
			}
			throw new RuntimeException(e);
		}
	}


	private static String safeSuffix(String name) {
		if (name == null || name.isBlank()) {
			return ".tmp";
		}
		String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
		int dot = safe.lastIndexOf('.');
		if (dot < 0 || dot == safe.length() - 1) {
			return ".tmp";
		}
		String extension = safe.substring(dot + 1).replaceAll("[^A-Za-z0-9]", "");
		if (extension.isBlank() || extension.length() > 10) {
			return ".tmp";
		}
		return "." + extension;
	}
}