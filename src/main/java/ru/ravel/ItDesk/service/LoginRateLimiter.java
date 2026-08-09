package ru.ravel.ItDesk.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local safety net for form-login brute force. Production deployments with
 * multiple instances should additionally rate-limit at the ingress/shared store.
 */
@Component
public class LoginRateLimiter {

    private static final long WINDOW_MS = Duration.ofMinutes(15).toMillis();
    private static final int MAX_PER_IP = 50;
    private static final int MAX_PER_IP_AND_ACCOUNT = 10;
    private static final int MAX_KEYS = 20_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String remoteAddress, String username) {
        long now = System.currentTimeMillis();
        if (windows.size() > MAX_KEYS) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_MS);
            if (windows.size() > MAX_KEYS) {
                return false;
            }
        }

        String ip = normalize(remoteAddress);
        String account = normalize(username);
        return increment("ip:" + ip, MAX_PER_IP, now)
                && increment("ip-user:" + ip + ":" + account, MAX_PER_IP_AND_ACCOUNT, now);
    }

    private boolean increment(String key, int limit, long now) {
        final boolean[] allowed = {false};
        windows.compute(key, (ignored, previous) -> {
            Window current = previous;
            if (current == null || now - current.startedAt >= WINDOW_MS) {
                current = new Window(now, 0);
            }
            if (current.count >= limit) {
                return current;
            }
            allowed[0] = true;
            return new Window(current.startedAt, current.count + 1);
        });
        return allowed[0];
    }

    private String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
    }

    private record Window(long startedAt, int count) {
    }
}
