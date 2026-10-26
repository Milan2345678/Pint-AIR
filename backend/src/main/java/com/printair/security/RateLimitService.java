package com.printair.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    public boolean allow(String key, int limit, long windowMillis) {
        long now = clock.millis();
        long windowStart = now / windowMillis * windowMillis;
        Window window = windows.compute(key, (ignored, current) ->
                current == null || current.startedAt != windowStart ? new Window(windowStart) : current);
        return window.incrementAndGet() <= limit;
    }

    @Scheduled(fixedDelay = 300_000)
    void clearOldWindows() {
        long cutoff = clock.millis() - 300_000;
        windows.entrySet().removeIf(entry -> entry.getValue().startedAt < cutoff);
    }

    private static final class Window {
        private final long startedAt;
        private int count;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }

        private synchronized int incrementAndGet() {
            return ++count;
        }
    }
}
