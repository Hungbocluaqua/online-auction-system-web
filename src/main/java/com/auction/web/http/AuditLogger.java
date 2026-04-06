package com.auction.web.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AuditLogger {
    private final Path filePath;
    private final Gson gson = new GsonBuilder().create();
    private final BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();

    public AuditLogger(Path filePath) {
        this.filePath = filePath;
        Thread loggerThread = new Thread(this::processQueue, "AuditLoggerWorker");
        loggerThread.setDaemon(true);
        loggerThread.start();
    }

    public void log(String action, String actor, String outcome, String target, String detail) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", System.currentTimeMillis());
        event.put("action", action);
        event.put("actor", actor);
        event.put("outcome", outcome);
        event.put("target", target);
        event.put("detail", detail);
        queue.offer(event);
    }

    public void shutdown() {
        List<Map<String, Object>> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        for (Map<String, Object> event : remaining) {
            try { writeLog(event); } catch (Exception ignored) {}
        }
    }

    private void processQueue() {
        while (true) {
            try {
                Map<String, Object> event = queue.take();
                writeLog(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void writeLog(Map<String, Object> event) {
        try {
            Files.createDirectories(filePath.getParent());
            try (Writer writer = Files.newBufferedWriter(
                filePath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )) {
                writer.write(gson.toJson(event));
                writer.write(System.lineSeparator());
            }
        } catch (IOException ignored) {
        }
    }
}
