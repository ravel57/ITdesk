package ru.ravel.ItDesk.component;

import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.ravel.ItDesk.repository.ClientRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;


/**
 * Tracks browser/API uploads until they are attached to a message.
 *
 * The object UUID itself is not treated as an authorization secret: the upload
 * owner must match the authenticated principal that references the UUID.
 * Metadata lives in Redis so ownership survives application restarts.
 */
@Component
@RequiredArgsConstructor
public class PendingFileUploadRegistry {

    private static final Duration OWNER_TTL = Duration.ofHours(24);
    private static final String OWNER_KEY_PREFIX = "security::pending-upload-owner::";
    private static final String EXPIRY_ZSET = "security::pending-upload-expiry";
    private static final int MAX_CLEANUP_BATCH = 500;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final RedisTemplate<String, byte[]> byteArrayRedisTemplate;
    private final MinioClient minioClient;
    private final ClientRepository clientRepository;

    @Value("${minio.bucket-name}")
    private String bucketName;

    private volatile boolean shuttingDown;


    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        if (event.getApplicationContext().getParent() == null) {
            shuttingDown = true;
        }
    }


    public void register(String fileUuid, String username) {
        String canonicalUuid = requireCanonicalUuid(fileUuid);
        String owner = requireUsername(username);
        byte[] uuidBytes = canonicalUuid.getBytes(StandardCharsets.UTF_8);
        byte[] ownerBytes = owner.getBytes(StandardCharsets.UTF_8);
        long expiresAt = System.currentTimeMillis() + OWNER_TTL.toMillis();

        byteArrayRedisTemplate.opsForValue().set(ownerKey(canonicalUuid), ownerBytes, OWNER_TTL);
        Boolean added = byteArrayRedisTemplate.opsForZSet().add(EXPIRY_ZSET, uuidBytes, expiresAt);
        if (!Boolean.TRUE.equals(added)) {
            // add() returning false can also mean "score updated for an existing member".
            // The owner key is authoritative, so no failure is raised here.
            logger.debug("Pending upload expiry member already existed for {}", canonicalUuid);
        }
    }


    public void assertOwned(String fileUuid, String username) {
        String canonicalUuid = requireCanonicalUuid(fileUuid);
        byte[] expected = requireUsername(username).getBytes(StandardCharsets.UTF_8);
        byte[] actual = byteArrayRedisTemplate.opsForValue().get(ownerKey(canonicalUuid));
        if (actual == null || !MessageDigest.isEqual(actual, expected)) {
            throw new AccessDeniedException("Файл не принадлежит текущему пользователю или срок загрузки истёк");
        }
    }


    /**
     * Consume ownership only after the surrounding DB transaction commits.
     * This prevents a rollback from turning the MinIO object into an
     * untracked orphan.
     */
    public void consumeAfterCommit(String fileUuid) {
        String canonicalUuid = requireCanonicalUuid(fileUuid);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    consume(canonicalUuid);
                }
            });
            return;
        }
        consume(canonicalUuid);
    }


    public void consume(String fileUuid) {
        if (fileUuid == null || fileUuid.isBlank()) {
            return;
        }
        String canonicalUuid = requireCanonicalUuid(fileUuid);
        byte[] uuidBytes = canonicalUuid.getBytes(StandardCharsets.UTF_8);
        byteArrayRedisTemplate.delete(ownerKey(canonicalUuid));
        byteArrayRedisTemplate.opsForZSet().remove(EXPIRY_ZSET, uuidBytes);
    }


    @Scheduled(fixedDelayString = "${app.files.pending-upload-cleanup-ms:3600000}")
    public void cleanupExpiredUploads() {
        if (shuttingDown) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            Set<byte[]> expired = byteArrayRedisTemplate.opsForZSet()
                    .rangeByScore(EXPIRY_ZSET, 0d, (double) now, 0, MAX_CLEANUP_BATCH);
            if (expired == null || expired.isEmpty()) {
                return;
            }
            int processed = 0;
            for (byte[] rawUuid : expired) {
                if (shuttingDown) {
                    return;
                }
                if (rawUuid == null || processed++ >= MAX_CLEANUP_BATCH) {
                    break;
                }
                String fileUuid = new String(rawUuid, StandardCharsets.UTF_8);
                try {
                    String canonicalUuid = requireCanonicalUuid(fileUuid);
                    if (!isLinkedToMessage(canonicalUuid)) {
                        minioClient.removeObject(
                                RemoveObjectArgs.builder()
                                        .bucket(bucketName)
                                        .object(canonicalUuid)
                                        .build()
                        );
                    }
                    consume(canonicalUuid);
                } catch (Exception cleanupException) {
                    if (shuttingDown) {
                        logger.debug("Pending upload cleanup interrupted by application shutdown", cleanupException);
                        return;
                    }
                    logger.warn("Failed to clean expired pending upload {}", fileUuid, cleanupException);
                }
            }
        } catch (Exception e) {
            if (shuttingDown) {
                logger.debug("Pending upload cleanup interrupted by application shutdown", e);
            } else {
                logger.warn("Pending upload cleanup failed", e);
            }
        }
    }


    private boolean isLinkedToMessage(String fileUuid) {
        return !clientRepository.findClientMessageFileAccessRowsByFileUuid(fileUuid).isEmpty()
                || !clientRepository.findTaskMessageFileAccessRowsByFileUuid(fileUuid).isEmpty();
    }


    private String ownerKey(String fileUuid) {
        return OWNER_KEY_PREFIX + fileUuid;
    }


    private String requireCanonicalUuid(String fileUuid) {
        try {
            UUID uuid = UUID.fromString(Objects.requireNonNull(fileUuid, "fileUuid").trim());
            String canonical = uuid.toString();
            if (!canonical.equals(fileUuid.trim())) {
                throw new IllegalArgumentException("Некорректный идентификатор файла");
            }
            return canonical;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Некорректный идентификатор файла", e);
        }
    }


    private String requireUsername(String username) {
        String normalized = Objects.toString(username, "").trim();
        if (normalized.isBlank()) {
            throw new AccessDeniedException("Не удалось определить владельца файла");
        }
        return normalized;
    }
}
