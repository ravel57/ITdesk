package ru.ravel.ItDesk.service;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;

@Service
@RequiredArgsConstructor
public class MinioService {

	private final MinioClient minioClient;

	@Value("${minio.bucket-name}")
	private String bucketName;


	public File getFile(String fileName, String fileUuid) {
		try (FileOutputStream fos = new FileOutputStream(safeFilename(fileName))) {
			GetObjectResponse getObjectResponse = minioClient.getObject(
					GetObjectArgs.builder()
							.bucket(bucketName)
							.object(fileUuid)
							.build());
			byte[] buf = new byte[8192];
			int bytesRead;
			while ((bytesRead = getObjectResponse.read(buf)) != -1) {
				fos.write(buf, 0, bytesRead);
			}
			return new File(safeFilename(fileName));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}


	private static String safeFilename(String name) {
		if (name == null || name.isBlank()) return "file";
		return name.replaceAll("[\\\\/:*?\"<>|]", "_");
	}

}