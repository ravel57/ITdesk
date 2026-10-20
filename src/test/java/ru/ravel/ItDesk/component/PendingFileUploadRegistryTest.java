package ru.ravel.ItDesk.component;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import ru.ravel.ItDesk.repository.ClientRepository;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PendingFileUploadRegistryTest {
    @Mock RedisTemplate<String, byte[]> redis;
    @Mock ZSetOperations<String, byte[]> expiry;
    @Mock MinioClient minio;
    @Mock ClientRepository clients;
    private PendingFileUploadRegistry registry;
    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        registry = new PendingFileUploadRegistry(redis, minio, clients);
        logger = (Logger) LoggerFactory.getLogger(PendingFileUploadRegistry.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
    }

    @Test
    void doesNotStartCleanupAfterRootContextCloses() {
        closeRootContext();
        registry.cleanupExpiredUploads();
        verifyNoInteractions(redis, minio, clients);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void suppressesShutdownWarningForAlreadyRunningRedisOperation() {
        when(redis.opsForZSet()).thenReturn(expiry);
        when(expiry.rangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    closeRootContext();
                    throw connectionClosed();
                });
        registry.cleanupExpiredUploads();
        assertThat(logs.list).isEmpty();
        verifyNoInteractions(minio, clients);
    }

    @Test
    void stopsBeforeDeletingFilesWhenShutdownBeginsDuringRedisRead() {
        when(redis.opsForZSet()).thenReturn(expiry);
        when(expiry.rangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    closeRootContext();
                    return Set.of(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
                });
        registry.cleanupExpiredUploads();
        verifyNoInteractions(minio, clients);
    }

    @Test
    void realRedisFailureStaysVisibleAndDoesNotDisableFutureCleanup() {
        when(redis.opsForZSet()).thenReturn(expiry);
        when(expiry.rangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenThrow(connectionClosed()).thenReturn(Set.of());
        registry.cleanupExpiredUploads();
        registry.cleanupExpiredUploads();
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.WARN);
        verify(expiry, times(2)).rangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong());
        verifyNoInteractions(minio, clients);
    }

    @Test
    void closingChildContextDoesNotStopRootCleanup() {
        ConfigurableApplicationContext child = mock(ConfigurableApplicationContext.class);
        when(child.getParent()).thenReturn(mock(ConfigurableApplicationContext.class));
        when(redis.opsForZSet()).thenReturn(expiry);
        registry.onContextClosed(new ContextClosedEvent(child));
        registry.cleanupExpiredUploads();
        verify(expiry).rangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong());
    }

    private void closeRootContext() {
        registry.onContextClosed(new ContextClosedEvent(mock(ConfigurableApplicationContext.class)));
    }

    private RedisSystemException connectionClosed() {
        return new RedisSystemException("Redis exception", new IllegalStateException("Connection is closed"));
    }
}
