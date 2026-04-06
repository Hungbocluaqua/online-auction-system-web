package com.auction.web.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class StaticFileHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        HttpUtil.addSecurityHeaders(exchange);
        String path = exchange.getRequestURI().getPath();
        
        // Serve from uploads directory
        if (path.startsWith("/uploads/")) {
            String filename = path.substring("/uploads/".length());
            if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                byte[] body = "Forbidden".getBytes(StandardCharsets.UTF_8);
                HttpUtil.writeBytes(exchange, 403, "text/plain; charset=UTF-8", body);
                return;
            }
            File uploadsDir = new File("uploads").getCanonicalFile();
            File file = new File(uploadsDir, filename);
            if (!file.exists()) {
                byte[] body = "Not found".getBytes(StandardCharsets.UTF_8);
                HttpUtil.writeBytes(exchange, 404, "text/plain; charset=UTF-8", body);
                return;
            }
            String canonicalPath = file.getCanonicalPath();
            if (!canonicalPath.startsWith(uploadsDir.getPath() + File.separator)) {
                byte[] body = "Forbidden".getBytes(StandardCharsets.UTF_8);
                HttpUtil.writeBytes(exchange, 403, "text/plain; charset=UTF-8", body);
                return;
            }
            if (file.isFile()) {
                byte[] body = Files.readAllBytes(file.toPath());
                HttpUtil.writeBytes(exchange, 200, contentType(path), body);
                return;
            }
        }

        // Serve from resources
        if (path.equals("/")) {
            path = "/index.html";
        }

        String resourcePath = "/static" + path;
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                byte[] body = "Not found".getBytes(StandardCharsets.UTF_8);
                HttpUtil.writeBytes(exchange, 404, "text/plain; charset=UTF-8", body);
                return;
            }
            byte[] body = stream.readAllBytes();
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            HttpUtil.writeBytes(exchange, 200, contentType(path), body);
        }
    }

    private String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
