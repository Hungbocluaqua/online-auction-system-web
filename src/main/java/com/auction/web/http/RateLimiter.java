package com.auction.web.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public void check(String key, int limit, long windowMillis, String message) {
        long now = System.currentTimeMillis();
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.windowStart >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(existing.windowStart, existing.count + 1);
        });
        if (window.count > limit) {
            throw new RateLimitException(message);
        }
    }

    private record Window(long windowStart, int count) {
    }
}
