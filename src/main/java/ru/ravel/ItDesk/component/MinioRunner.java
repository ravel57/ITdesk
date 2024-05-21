package ru.ravel.ItDesk.component;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MinioRunner implements CommandLineRunner {

	private final MinioClient minioClient;
	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Value("minio.bucketName")
	String bucketName;


	@Override
	public void run(String... args) throws Exception {
		try {
			boolean isExist = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
			if (isExist) {
				logger.debug("Bucket already exists.");
			} else {
				minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
				logger.debug("Bucket created successfully.");
			}
		} catch (Exception e) {
			logger.error(e.getMessage());
		}
	}
}
