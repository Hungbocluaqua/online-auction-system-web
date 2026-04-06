package com.auction.web.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RateLimiter {
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    public RateLimiter() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RateLimiter-Cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::cleanup, 5, 5, TimeUnit.MINUTES);
    }

    public void shutdown() {
        cleanupExecutor.shutdownNow();
    }

    public void check(String key, int limit, long windowMillis, String message) {
        long now = System.currentTimeMillis();
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.windowStart >= windowMillis) {
                return new Window(now, 1, windowMillis);
            }
            return new Window(existing.windowStart, existing.count + 1, existing.maxAge);
        });
        if (window.count > limit) {
            throw new RateLimitException(message);
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().windowStart > e.getValue().maxAge);
    }

    private record Window(long windowStart, int count, long maxAge) {
    }
}
