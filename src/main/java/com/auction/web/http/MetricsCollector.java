package com.auction.web.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class MetricsCollector {
    private static volatile MetricsCollector instance;
    private final AtomicLong authAttempts = new AtomicLong();
    private final AtomicLong authFailures = new AtomicLong();
    private final AtomicLong registrations = new AtomicLong();
    private final AtomicLong auctionCreates = new AtomicLong();
    private final AtomicLong bidsPlaced = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final Map<Integer, AtomicLong> requestCounts = new ConcurrentHashMap<>();

    private MetricsCollector() {}

    public static MetricsCollector getInstance() {
        if (instance == null) {
            synchronized (MetricsCollector.class) {
                if (instance == null) instance = new MetricsCollector();
            }
        }
        return instance;
    }

    public void recordAuthAttempt() { authAttempts.incrementAndGet(); }
    public void recordAuthFailure() { authFailures.incrementAndGet(); }
    public void recordRegistration() { registrations.incrementAndGet(); }
    public void recordAuctionCreate() { auctionCreates.incrementAndGet(); }
    public void recordBid() { bidsPlaced.incrementAndGet(); }
    public void recordError() { errors.incrementAndGet(); }
    public void recordRequest(int status) {
        requestCounts.computeIfAbsent(status, k -> new AtomicLong()).incrementAndGet();
    }

    public long getAuthAttempts() { return authAttempts.get(); }
    public long getAuthFailures() { return authFailures.get(); }
    public long getRegistrations() { return registrations.get(); }
    public long getAuctionCreates() { return auctionCreates.get(); }
    public long getBidsPlaced() { return bidsPlaced.get(); }
    public long getErrors() { return errors.get(); }
    public Map<Integer, Long> getRequestCounts() {
        Map<Integer, Long> result = new ConcurrentHashMap<>();
        for (Map.Entry<Integer, AtomicLong> e : requestCounts.entrySet()) {
            result.put(e.getKey(), e.getValue().get());
        }
        return result;
    }
}
