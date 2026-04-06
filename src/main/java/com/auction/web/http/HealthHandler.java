package com.auction.web.http;

import com.auction.web.service.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

public class HealthHandler implements HttpHandler {
    private static volatile long applicationStartTime = System.currentTimeMillis();
    private final DatabaseManager db;
    private final Gson gson = new GsonBuilder().create();

    public HealthHandler(DatabaseManager db) {
        this.db = db;
    }

    public static void setStartTime(long time) {
        applicationStartTime = time;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        HttpUtil.addSecurityHeaders(exchange);
        String path = exchange.getRequestURI().getPath();
        if ("/api/health".equals(path)) handleHealth(exchange);
        else if ("/api/health/live".equals(path)) handleLiveness(exchange);
        else if ("/api/health/ready".equals(path)) handleReadiness(exchange);
        else if ("/api/metrics".equals(path)) handleMetrics(exchange);
        else exchange.sendResponseHeaders(404, -1);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("uptime_ms", System.currentTimeMillis() - applicationStartTime);
        body.put("db", checkDb().equals("OK") ? "OK" : "ERROR");
        sendJson(exchange, 200, body);
    }

    private void handleLiveness(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        sendJson(exchange, 200, body);
    }

    private void handleReadiness(HttpExchange exchange) throws IOException {
        String dbStatus = checkDb();
        int status = "OK".equals(dbStatus) ? 200 : 503;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK".equals(dbStatus) ? "UP" : "DOWN");
        body.put("db", dbStatus);
        sendJson(exchange, status, body);
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        MetricsCollector metrics = MetricsCollector.getInstance();
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP auction_auth_attempts_total Total authentication attempts\n");
        sb.append("# TYPE auction_auth_attempts_total counter\n");
        sb.append("auction_auth_attempts_total ").append(metrics.getAuthAttempts()).append("\n");
        sb.append("# HELP auction_auth_failures_total Total authentication failures\n");
        sb.append("# TYPE auction_auth_failures_total counter\n");
        sb.append("auction_auth_failures_total ").append(metrics.getAuthFailures()).append("\n");
        sb.append("# HELP auction_registrations_total Total user registrations\n");
        sb.append("# TYPE auction_registrations_total counter\n");
        sb.append("auction_registrations_total ").append(metrics.getRegistrations()).append("\n");
        sb.append("# HELP auction_created_total Total auctions created\n");
        sb.append("# TYPE auction_created_total counter\n");
        sb.append("auction_created_total ").append(metrics.getAuctionCreates()).append("\n");
        sb.append("# HELP auction_bids_total Total bids placed\n");
        sb.append("# TYPE auction_bids_total counter\n");
        sb.append("auction_bids_total ").append(metrics.getBidsPlaced()).append("\n");
        sb.append("# HELP auction_errors_total Total server errors\n");
        sb.append("# TYPE auction_errors_total counter\n");
        sb.append("auction_errors_total ").append(metrics.getErrors()).append("\n");
        sb.append("# HELP auction_requests_total Total requests by status\n");
        sb.append("# TYPE auction_requests_total counter\n");
        for (Map.Entry<Integer, Long> entry : metrics.getRequestCounts().entrySet()) {
            sb.append("auction_requests_total{status=\"").append(entry.getKey()).append("\"} ").append(entry.getValue()).append("\n");
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String checkDb() {
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1")) {
            return rs.next() ? "OK" : "ERROR";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private void sendJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
