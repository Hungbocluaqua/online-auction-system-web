package com.auction.web.http;

import com.auction.web.dto.ApiResponse;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HttpUtil {
    public static final String SESSION_COOKIE = "auction_session";
    private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build();

    private HttpUtil() {
    }

    public static HttpClient sharedClient() {
        return SHARED_CLIENT;
    }

    public static String readBody(HttpExchange exchange) throws IOException {
        return new String(readBodyBytes(exchange), StandardCharsets.UTF_8);
    }

    public static <T> T readBody(HttpExchange exchange, Gson gson, Class<T> clazz) throws IOException {
        try {
            return gson.fromJson(readBody(exchange), clazz);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid request body");
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readBodyMap(HttpExchange exchange, Gson gson) throws IOException {
        return readBody(exchange, gson, Map.class);
    }

    public static byte[] readBodyBytes(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return is.readAllBytes();
        }
    }

    public static void writeJson(HttpExchange exchange, int statusCode, Gson gson, ApiResponse response) throws IOException {
        byte[] body = gson.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        MetricsCollector.getInstance().recordRequest(statusCode);
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    public static void writeBytes(HttpExchange exchange, int statusCode, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    public static String bearerToken(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length()).trim();
    }

    public static String authToken(HttpExchange exchange) {
        String bearer = bearerToken(exchange);
        if (bearer != null && !bearer.isBlank()) {
            return bearer;
        }
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return null;
        }
        for (String cookie : cookieHeader.split(";")) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith(SESSION_COOKIE + "=")) {
                return trimmed.substring((SESSION_COOKIE + "=").length()).trim();
            }
        }
        return null;
    }

    public static void setSessionCookie(HttpExchange exchange, String token) {
        String secure = isHttps(exchange) ? "; Secure" : "";
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax" + secure);
    }

    public static void clearSessionCookie(HttpExchange exchange) {
        String secure = isHttps(exchange) ? "; Secure" : "";
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" + secure);
    }

    public static String clientIp(HttpExchange exchange) {
        String forwardedFor = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = exchange.getRequestHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    public static void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");
        exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self' ws: wss:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
    }

    public static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }

    public static byte[] hmacSha256Raw(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static Map<String, Object> parseJsonToMap(String json) {
        try {
            Gson gson = new Gson();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = gson.fromJson(json, Map.class);
            return parsed;
        } catch (Exception e) {
            return null;
        }
    }

    public static String stringField(Map<String, ?> body, String key) {
        if (body == null || key == null) {
            return null;
        }
        Object rawValue = body.get(key);
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof String s) {
            return s.trim();
        }
        if (rawValue instanceof Number || rawValue instanceof Boolean || rawValue instanceof Character) {
            return String.valueOf(rawValue);
        }
        return null;
    }

    public static long requireLongField(Map<String, ?> body, String key) {
        if (body == null || key == null) {
            throw new IllegalArgumentException("Invalid request body");
        }
        Object rawValue = body.get(key);
        if (rawValue == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        if (rawValue instanceof Number numberValue) {
            double numericValue = numberValue.doubleValue();
            if (!Double.isFinite(numericValue) || Math.rint(numericValue) != numericValue) {
                throw new IllegalArgumentException(key + " must be a whole number");
            }
            return numberValue.longValue();
        }

        String value = stringField(body, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number");
        }
    }

    private static boolean isHttps(HttpExchange exchange) {
        String forwardedProto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(forwardedProto);
    }
}
