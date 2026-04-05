package com.auction.web.http;

import com.auction.web.dto.ApiResponse;
import com.auction.web.dto.AuctionCreateRequest;
import com.auction.web.dto.AuctionLimitUpdateRequest;
import com.auction.web.dto.AuctionUpdateRequest;
import com.auction.web.dto.AuthRequest;
import com.auction.web.dto.AutoBidRequest;
import com.auction.web.dto.BidRequest;
import com.auction.web.dto.DeleteAccountRequest;
import com.auction.web.dto.PasswordChangeRequest;
import com.auction.web.dto.RegisterRequest;
import com.auction.web.dto.UsernameUpdateRequest;
import com.auction.web.service.AuctionService;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public class ApiHandler implements HttpHandler {
    private final AuctionService service;
    private final Gson gson;
    private final RateLimiter rateLimiter = new RateLimiter();
    private final AuditLogger auditLogger = new AuditLogger(Path.of("data", "audit.log"));

    public ApiHandler(AuctionService service, Gson gson) {
        this.service = service;
        this.gson = gson;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            addCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String token = HttpUtil.authToken(exchange);
            String clientIp = HttpUtil.clientIp(exchange);

            if ("/api/login".equals(path) && "POST".equalsIgnoreCase(method)) {
                rateLimiter.check("login:" + clientIp, 10, 10 * 60_000L, "Too many login attempts");
                AuthRequest request = gson.fromJson(HttpUtil.readBody(exchange), AuthRequest.class);
                var session = service.login(request.username, request.password);
                HttpUtil.setSessionCookie(exchange, session.getToken());
                auditLogger.log("login", request.username, "success", "session", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(session));
                return;
            }
            if ("/api/register".equals(path) && "POST".equalsIgnoreCase(method)) {
                rateLimiter.check("register:" + clientIp, 5, 10 * 60_000L, "Too many registration attempts");
                RegisterRequest request = gson.fromJson(HttpUtil.readBody(exchange), RegisterRequest.class);
                var session = service.register(request.username, request.password, request.role);
                HttpUtil.setSessionCookie(exchange, session.getToken());
                auditLogger.log("register", request.username, "success", "account", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(session));
                return;
            }
            if ("/api/session".equals(path) && "GET".equalsIgnoreCase(method)) {
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.requireSession(token)));
                return;
            }
            if ("/api/account/password".equals(path) && "POST".equalsIgnoreCase(method)) {
                rateLimiter.check("account-password:" + clientIp, 10, 10 * 60_000L, "Too many password change attempts");
                PasswordChangeRequest request = gson.fromJson(HttpUtil.readBody(exchange), PasswordChangeRequest.class);
                var session = service.changePassword(token, request.currentPassword, request.newPassword);
                auditLogger.log("password_change", session.getUsername(), "success", "account", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(session));
                return;
            }
            if ("/api/account/profile".equals(path) && "PUT".equalsIgnoreCase(method)) {
                UsernameUpdateRequest request = gson.fromJson(HttpUtil.readBody(exchange), UsernameUpdateRequest.class);
                var session = service.updateUsername(token, request.username);
                auditLogger.log("username_change", session.getUsername(), "success", "account", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(session));
                return;
            }
            if ("/api/account/delete".equals(path) && "POST".equalsIgnoreCase(method)) {
                DeleteAccountRequest request = gson.fromJson(HttpUtil.readBody(exchange), DeleteAccountRequest.class);
                String username = safeUsername(token);
                String result = service.deleteAccount(token, request.password);
                HttpUtil.clearSessionCookie(exchange);
                auditLogger.log("account_delete", username, "success", "account", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
                return;
            }
            if ("/api/logout".equals(path) && "POST".equalsIgnoreCase(method)) {
                String username = safeUsername(token);
                service.logout(token);
                HttpUtil.clearSessionCookie(exchange);
                auditLogger.log("logout", username, "success", "session", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok("Logged out"));
                return;
            }
            if ("/api/auctions".equals(path) && "GET".equalsIgnoreCase(method)) {
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getAuctions()));
                return;
            }
            if ("/api/auctions".equals(path) && "POST".equalsIgnoreCase(method)) {
                AuctionCreateRequest request = gson.fromJson(HttpUtil.readBody(exchange), AuctionCreateRequest.class);
                var auction = service.createAuction(token, request);
                auditLogger.log("auction_create", safeUsername(token), "success", auction.getId(), clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(auction));
                return;
            }
            if ("/api/events".equals(path) && "GET".equalsIgnoreCase(method)) {
                String query = exchange.getRequestURI().getQuery();
                long since = 0;
                if (query != null && query.startsWith("since=")) {
                    try {
                        since = Long.parseLong(query.substring(6));
                    } catch (NumberFormatException ignored) {
                    }
                }
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getEvents(since)));
                return;
            }
            if ("/api/upload".equals(path) && "POST".equalsIgnoreCase(method)) {
                service.requireUser(token); // Ensure logged in
                String filename = UUID.randomUUID().toString() + ".png";
                byte[] bytes = HttpUtil.readBodyBytes(exchange);
                if (bytes.length > 5 * 1024 * 1024) {
                    throw new IllegalArgumentException("File too large (max 5MB)");
                }
                java.nio.file.Files.write(java.nio.file.Path.of("uploads", filename), bytes);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(Map.of("filename", filename)));
                return;
            }
            if ("/api/users".equals(path) && "GET".equalsIgnoreCase(method)) {
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getUsers(token)));
                return;
            }
            if (path.startsWith("/api/users/")) {
                handleUserSubroute(exchange, path, method, token, clientIp);
                return;
            }
            if (path.startsWith("/api/auctions/")) {
                handleAuctionSubroute(exchange, path, method, token, clientIp);
                return;
            }

            HttpUtil.writeJson(exchange, 404, gson, ApiResponse.error("Not found"));
        } catch (RateLimitException ex) {
            HttpUtil.writeJson(exchange, 429, gson, ApiResponse.error(ex.getMessage()));
        } catch (SecurityException ex) {
            auditLogger.log("request_denied", safeUsername(HttpUtil.authToken(exchange)), "denied", exchange.getRequestURI().getPath(), ex.getMessage());
            HttpUtil.writeJson(exchange, 401, gson, ApiResponse.error(ex.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            HttpUtil.writeJson(exchange, 400, gson, ApiResponse.error(ex.getMessage()));
        } catch (Exception ex) {
            ex.printStackTrace();
            HttpUtil.writeJson(exchange, 500, gson, ApiResponse.error("Internal server error"));
        }
    }

    private void handleUserSubroute(HttpExchange exchange, String path, String method, String token, String clientIp) throws IOException {
        String suffix = path.substring("/api/users/".length());
        String[] parts = suffix.split("/");
        String username = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);

        if (parts.length == 2 && "auction-limit".equals(parts[1]) && "PUT".equalsIgnoreCase(method)) {
            AuctionLimitUpdateRequest request = gson.fromJson(HttpUtil.readBody(exchange), AuctionLimitUpdateRequest.class);
            var users = service.updateAuctionLimit(token, username, request.auctionLimit);
            auditLogger.log("auction_limit_update", safeUsername(token), "success", username, String.valueOf(request.auctionLimit));
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(users));
            return;
        }

        HttpUtil.writeJson(exchange, 404, gson, ApiResponse.error("Not found"));
    }

    private void handleAuctionSubroute(HttpExchange exchange, String path, String method, String token, String clientIp) throws IOException {
        String suffix = path.substring("/api/auctions/".length());
        String[] parts = suffix.split("/");
        String auctionId = parts[0];

        if (parts.length == 1 && "GET".equalsIgnoreCase(method)) {
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getAuction(auctionId)));
            return;
        }
        if (parts.length == 1 && "PUT".equalsIgnoreCase(method)) {
            AuctionUpdateRequest request = gson.fromJson(HttpUtil.readBody(exchange), AuctionUpdateRequest.class);
            var auction = service.updateAuction(token, auctionId, request);
            auditLogger.log("auction_update", safeUsername(token), "success", auctionId, clientIp);
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(auction));
            return;
        }
        if (parts.length == 1 && "DELETE".equalsIgnoreCase(method)) {
            service.deleteAuction(token, auctionId);
            auditLogger.log("auction_delete", safeUsername(token), "success", auctionId, clientIp);
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok("Auction deleted"));
            return;
        }
        if (parts.length == 2 && "bid".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
            rateLimiter.check("bid:" + clientIp, 30, 60_000L, "Too many bids, please slow down");
            BidRequest request = gson.fromJson(HttpUtil.readBody(exchange), BidRequest.class);
            var auction = service.placeBid(token, auctionId, request.amount);
            auditLogger.log("bid_place", safeUsername(token), "success", auctionId, String.valueOf(request.amount));
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(auction));
            return;
        }
        if (parts.length == 2 && "autobid".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
            AutoBidRequest request = gson.fromJson(HttpUtil.readBody(exchange), AutoBidRequest.class);
            var auction = service.configureAutoBid(token, auctionId, request.maxBid, request.increment);
            auditLogger.log("autobid_configure", safeUsername(token), "success", auctionId, String.valueOf(request.maxBid));
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(auction));
            return;
        }
        if (parts.length == 2 && "cancel".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
            var auction = service.cancelAuction(token, auctionId);
            auditLogger.log("auction_cancel", safeUsername(token), "success", auctionId, clientIp);
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(auction));
            return;
        }

        HttpUtil.writeJson(exchange, 404, gson, ApiResponse.error("Not found"));
    }

    private void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
    }

    private String safeUsername(String token) {
        try {
            return service.requireSession(token).getUsername();
        } catch (Exception ignored) {
            return "anonymous";
        }
    }
}
