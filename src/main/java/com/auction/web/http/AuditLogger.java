package com.auction.web.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

public class AuditLogger {
    private final Path filePath;
    private final Gson gson = new GsonBuilder().create();

    public AuditLogger(Path filePath) {
        this.filePath = filePath;
    }

    public synchronized void log(String action, String actor, String outcome, String target, String detail) {
        try {
            Files.createDirectories(filePath.getParent());
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("timestamp", System.currentTimeMillis());
            event.put("action", action);
            event.put("actor", actor);
            event.put("outcome", outcome);
            event.put("target", target);
            event.put("detail", detail);
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
