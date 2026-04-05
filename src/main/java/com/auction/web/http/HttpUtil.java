package com.auction.web.http;

import com.auction.web.dto.ApiResponse;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class HttpUtil {
    public static final String SESSION_COOKIE = "auction_session";

    private HttpUtil() {
    }

    public static String readBody(HttpExchange exchange) throws IOException {
        return new String(readBodyBytes(exchange), StandardCharsets.UTF_8);
    }

    public static byte[] readBodyBytes(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return is.readAllBytes();
        }
    }

    public static void writeJson(HttpExchange exchange, int statusCode, Gson gson, ApiResponse response) throws IOException {
        byte[] body = gson.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
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
        List<String> forwarded = exchange.getRequestHeaders().get("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.get(0).split(",")[0].trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private static boolean isHttps(HttpExchange exchange) {
        String forwardedProto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(forwardedProto);
    }
}
