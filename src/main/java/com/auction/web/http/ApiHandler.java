package com.auction.web.http;

import com.auction.web.dto.*;
import com.auction.web.service.AuctionService;
import com.auction.web.service.ZaloPayService;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ApiHandler implements HttpHandler {
    private final AuctionService service;
    private final ZaloPayService zaloPayService;
    private final Gson gson;
    private final AuditLogger auditLogger = new AuditLogger(java.nio.file.Path.of("data", "audit.log"));
    private final RateLimiter rateLimiter = new RateLimiter();
    private final MetricsCollector metrics = MetricsCollector.getInstance();
    private final Map<String, CaptchaChallenge> captchaStore = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    private static class CaptchaChallenge {
        final int answer;
        final long expiresAt;
        CaptchaChallenge(int answer, long ttlMs) {
            this.answer = answer;
            this.expiresAt = System.currentTimeMillis() + ttlMs;
        }
        boolean isValid() { return System.currentTimeMillis() < expiresAt; }
    }

    public ApiHandler(AuctionService service, ZaloPayService zaloPayService, Gson gson) {
        this.service = service;
        this.zaloPayService = zaloPayService;
        this.gson = gson;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCors(exchange);
        addSecurityHeaders(exchange);
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String token = HttpUtil.authToken(exchange);
        String clientIp = HttpUtil.clientIp(exchange);

        if ("/api/login".equals(path) && "POST".equalsIgnoreCase(method)) metrics.recordAuthAttempt();
        if ("/api/register".equals(path) && "POST".equalsIgnoreCase(method)) metrics.recordRegistration();
        if ("/api/auctions".equals(path) && "POST".equalsIgnoreCase(method)) metrics.recordAuctionCreate();

        try {
            if ("/api/captcha".equals(path) && "GET".equalsIgnoreCase(method)) {
                int a = secureRandom.nextInt(50) + 10;
                int b = secureRandom.nextInt(50) + 10;
                int c = secureRandom.nextInt(20) + 1;
                String challengeId = java.util.UUID.randomUUID().toString();
                captchaStore.put(challengeId, new CaptchaChallenge(a + b - c, 120000));
                if (captchaStore.size() > 10000) captchaStore.entrySet().removeIf(e -> !e.getValue().isValid());
                Map<String, Object> resp = new java.util.LinkedHashMap<>();
                resp.put("challengeId", challengeId);
                resp.put("question", "What is " + a + " + " + b + " - " + c + "?");
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(resp));
                return;
            }
            if ("/api/login".equals(path) && "POST".equalsIgnoreCase(method)) {
                rateLimiter.check(clientIp, 10, 600000, "Too many login attempts");
                AuthRequest request = HttpUtil.readBody(exchange, gson, AuthRequest.class);
                validateCaptcha(request.captchaChallengeId, request.captchaAnswer);
                SessionView session = service.login(request.username, request.password);
                HttpUtil.setSessionCookie(exchange, session.getToken());
                auditLogger.log("login", session.getUsername(), "success", "session", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(session));
                return;
            }
            if ("/api/register".equals(path) && "POST".equalsIgnoreCase(method)) {
                rateLimiter.check(clientIp, 5, 600000, "Too many registration attempts");
                RegisterRequest request = HttpUtil.readBody(exchange, gson, RegisterRequest.class);
                validateCaptcha(request.captchaChallengeId, request.captchaAnswer);
                SessionView session = service.register(request.username, request.password, request.role);
                HttpUtil.setSessionCookie(exchange, session.getToken());
                auditLogger.log("register", session.getUsername(), "success", "account", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(session));
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
            if ("/api/session".equals(path) && "GET".equalsIgnoreCase(method)) {
                SessionView session = service.requireSession(token);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(session));
                return;
            }
            if ("/api/auctions".equals(path) && "GET".equalsIgnoreCase(method)) {
                String query = exchange.getRequestURI().getQuery();
                int page = 1, limit = 20;
                String category = null, search = null;
                if (query != null) {
                    for (String param : query.split("&")) {
                        if (param.startsWith("page=")) { try { page = Integer.parseInt(param.substring(5)); } catch (NumberFormatException ignored) {} }
                        if (param.startsWith("limit=")) { try { limit = Integer.parseInt(param.substring(6)); } catch (NumberFormatException ignored) {} }
                        if (param.startsWith("category=")) { try { category = URLDecoder.decode(param.substring(9), StandardCharsets.UTF_8); } catch (Exception ignored) {} }
                        if (param.startsWith("search=")) { try { search = URLDecoder.decode(param.substring(7), StandardCharsets.UTF_8); } catch (Exception ignored) {} }
                    }
                }
                limit = Math.min(Math.max(limit, 1), 100);
                page = Math.max(page, 1);
                Map<String, Object> result = service.getAuctionsPaginated(page, limit, category, search);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
                return;
            }
            if ("/api/categories".equals(path) && "GET".equalsIgnoreCase(method)) {
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getCategories()));
                return;
            }
            if ("/api/upload".equals(path) && "POST".equalsIgnoreCase(method)) {
                service.requireUser(token);
                byte[] bytes = HttpUtil.readBodyBytes(exchange);
                if (bytes.length > 10 * 1024 * 1024) { HttpUtil.writeJson(exchange, 400, gson, ApiResponse.error("File too large (max 10MB)")); return; }
                String ext = detectImageType(bytes);
                if (ext == null) { HttpUtil.writeJson(exchange, 400, gson, ApiResponse.error("Invalid file type. Allowed: JPEG, PNG, GIF, WebP")); return; }
                String filename = java.util.UUID.randomUUID().toString() + "." + ext;
                java.nio.file.Path uploadDir = java.nio.file.Path.of("uploads");
                java.nio.file.Files.createDirectories(uploadDir);
                java.nio.file.Files.write(uploadDir.resolve(filename), bytes);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(Map.of("filename", filename)));
                return;
            }
            if ("/api/events".equals(path) && "GET".equalsIgnoreCase(method)) {
                String query = exchange.getRequestURI().getQuery();
                long since = 0;
                if (query != null) {
                    for (String param : query.split("&")) {
                        if (param.startsWith("since=")) { try { since = Long.parseLong(param.substring(6)); } catch (NumberFormatException ignored) {} }
                    }
                }
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getEvents(since)));
                return;
            }
            if ("/api/users".equals(path) && "GET".equalsIgnoreCase(method)) {
                rateLimiter.check(clientIp + ":users", 30, 60000, "Too many user listing requests");
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getUsers(token)));
                return;
            }
            if ("/api/notifications".equals(path) && "GET".equalsIgnoreCase(method)) {
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getNotifications(token)));
                return;
            }
            if (path.matches("^/api/notifications/[^/]+/read$") && "PUT".equalsIgnoreCase(method)) {
                requireCsrf(exchange, token);
                String notificationId = path.split("/")[3];
                String result = service.markNotificationRead(token, notificationId);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
                return;
            }
            if ("/api/watchlist".equals(path) && "GET".equalsIgnoreCase(method)) {
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getWatchlist(token)));
                return;
            }
            if ("/api/watchlist".equals(path) && "POST".equalsIgnoreCase(method)) {
                requireCsrf(exchange, token);
                Map<String, String> body = HttpUtil.readBody(exchange, gson, Map.class);
                String auctionId = body.get("auctionId");
                if (auctionId == null || auctionId.isBlank()) { HttpUtil.writeJson(exchange, 400, gson, ApiResponse.error("auctionId is required")); return; }
                String result = service.addToWatchlist(token, auctionId);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
                return;
            }
            if ("/api/invoices".equals(path) && "GET".equalsIgnoreCase(method)) {
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getInvoices(token)));
                return;
            }
            if (path.matches("^/api/watchlist/[^/]+$") && "DELETE".equalsIgnoreCase(method)) {
                requireCsrf(exchange, token);
                String auctionId = path.split("/")[3];
                String result = service.removeFromWatchlist(token, auctionId);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
                return;
            }
            if ("/api/account/history".equals(path) && "GET".equalsIgnoreCase(method)) {
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getAccountHistory(token)));
                return;
            }
            if ("/api/account/2fa/setup".equals(path) && "POST".equalsIgnoreCase(method)) {
                requireCsrf(exchange, token);
                Map<String, Object> setup = service.setup2FA(token);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(setup));
                return;
            }
            if ("/api/account/2fa/enable".equals(path) && "POST".equalsIgnoreCase(method)) {
                requireCsrf(exchange, token);
                Map<String, String> body = HttpUtil.readBody(exchange, gson, Map.class);
                String code = body.get("code");
                if (code == null || code.isBlank()) { HttpUtil.writeJson(exchange, 400, gson, ApiResponse.error("TOTP code is required")); return; }
                String result = service.enable2FA(token, code);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
                return;
            }
            if ("/api/account/2fa/disable".equals(path) && "POST".equalsIgnoreCase(method)) {
                requireCsrf(exchange, token);
                Map<String, String> body = HttpUtil.readBody(exchange, gson, Map.class);
                String code = body.get("code");
                if (code == null || code.isBlank()) { HttpUtil.writeJson(exchange, 400, gson, ApiResponse.error("TOTP code is required")); return; }
                String result = service.disable2FA(token, code);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
                return;
            }
            if ("/api/account/password".equals(path) && "PUT".equalsIgnoreCase(method)) {
                requireCsrf(exchange, token);
                rateLimiter.check(clientIp + ":pwd", 5, 600000, "Too many password change attempts");
                PasswordChangeRequest request = HttpUtil.readBody(exchange, gson, PasswordChangeRequest.class);
                SessionView session = service.changePassword(token, request.currentPassword, request.newPassword);
                auditLogger.log("password_change", safeUsername(token), "success", "account", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(session));
                return;
            }
            if ("/api/account/username".equals(path) && "PUT".equalsIgnoreCase(method)) {
                requireCsrf(exchange, token);
                UsernameUpdateRequest request = HttpUtil.readBody(exchange, gson, UsernameUpdateRequest.class);
                SessionView session = service.updateUsername(token, request.username);
                auditLogger.log("username_change", safeUsername(token), "success", "account", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(session));
                return;
            }
            if ("/api/account".equals(path) && "DELETE".equalsIgnoreCase(method)) {
                requireCsrf(exchange, token);
                rateLimiter.check(clientIp + ":del", 3, 600000, "Too many account deletion attempts");
                DeleteAccountRequest request = HttpUtil.readBody(exchange, gson, DeleteAccountRequest.class);
                String result = service.deleteAccount(token, request.password);
                HttpUtil.clearSessionCookie(exchange);
                auditLogger.log("account_delete", safeUsername(token), "success", "account", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
                return;
            }
            if ("/api/account/email".equals(path) && "PUT".equalsIgnoreCase(method)) {
                requireCsrf(exchange, token);
                EmailUpdateRequest request = HttpUtil.readBody(exchange, gson, EmailUpdateRequest.class);
                String result = service.requestEmailVerification(token, request.email);
                auditLogger.log("email_update", safeUsername(token), "success", "account", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
                return;
            }
            if ("/api/account/verify-email".equals(path) && "POST".equalsIgnoreCase(method)) {
                EmailVerifyRequest request = HttpUtil.readBody(exchange, gson, EmailVerifyRequest.class);
                SessionView session = service.confirmEmailVerification(request.token);
                auditLogger.log("email_verify", session.getUsername(), "success", "account", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(session));
                return;
            }
            if ("/api/password-reset/request".equals(path) && "POST".equalsIgnoreCase(method)) {
                rateLimiter.check(clientIp + ":reset", 3, 600000, "Too many password reset attempts");
                PasswordResetRequest request = HttpUtil.readBody(exchange, gson, PasswordResetRequest.class);
                String result = service.requestPasswordReset(request.username);
                auditLogger.log("password_reset_request", request.username, "success", "account", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
                return;
            }
            if ("/api/password-reset/confirm".equals(path) && "POST".equalsIgnoreCase(method)) {
                PasswordResetConfirmRequest request = HttpUtil.readBody(exchange, gson, PasswordResetConfirmRequest.class);
                SessionView session = service.confirmPasswordReset(request.token, request.newPassword);
                HttpUtil.setSessionCookie(exchange, session.getToken());
                auditLogger.log("password_reset_confirm", session.getUsername(), "success", "account", clientIp);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(session));
                return;
            }
            if ("/api/csrf".equals(path) && "GET".equalsIgnoreCase(method)) {
                SessionView session = service.requireSession(token);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(new CsrfTokenResponse(session.getCsrfToken())));
                return;
            }
            if ("/api/auctions".equals(path) && "POST".equalsIgnoreCase(method)) {
                requireCsrf(exchange, token);
                rateLimiter.check(clientIp + ":create", 10, 600000, "Too many auction creation attempts");
                AuctionCreateRequest request = HttpUtil.readBody(exchange, gson, AuctionCreateRequest.class);
                AuctionView auction = service.createAuction(token, request);
                auditLogger.log("auction_create", safeUsername(token), "success", auction.getId(), auction.getItemName());
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(auction));
                return;
            }
            if (path.startsWith("/api/users/")) { handleUserSubroute(exchange, path, method, token, clientIp); return; }
            if (path.startsWith("/api/auctions/")) { handleAuctionSubroute(exchange, path, method, token, clientIp); return; }
            if (path.startsWith("/api/admin/")) { handleAdminSubroute(exchange, path, method, token, clientIp); return; }
            if ("/api/zalopay/callback".equals(path) && "POST".equalsIgnoreCase(method)) {
                Map<String, String> body = HttpUtil.readBody(exchange, gson, Map.class);
                String data = body.get("data");
                String mac = body.get("mac");
                if (data == null || mac == null) { HttpUtil.writeJson(exchange, 400, gson, ApiResponse.error("Missing data or mac")); return; }
                if (!zaloPayService.verifyCallback(data, mac)) { HttpUtil.writeJson(exchange, 403, gson, ApiResponse.error("Invalid MAC signature")); return; }
                com.google.gson.JsonObject json = gson.fromJson(data, com.google.gson.JsonObject.class);
                String appTransId = json.get("app_trans_id").getAsString();
                String zpTransId = json.has("zp_trans_id") ? json.get("zp_trans_id").getAsString() : "";
                long amount = json.has("amount") ? json.get("amount").getAsLong() : 0;
                int status = json.has("status") ? json.get("status").getAsInt() : 0;
                Map<String, Object> result = service.confirmPaymentFromCallback(appTransId, zpTransId, amount, status);
                HttpUtil.writeJson(exchange, 200, gson, new ApiResponse(true, null, result));
                return;
            }
            if ("/api/zalopay/query".equals(path) && "POST".equalsIgnoreCase(method)) {
                requireCsrf(exchange, token);
                Map<String, String> body = HttpUtil.readBody(exchange, gson, Map.class);
                String paymentId = body.get("paymentId");
                if (paymentId == null || paymentId.isBlank()) { HttpUtil.writeJson(exchange, 400, gson, ApiResponse.error("paymentId is required")); return; }
                Map<String, Object> result = service.queryPaymentStatus(paymentId);
                HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
                return;
            }

            HttpUtil.writeJson(exchange, 404, gson, ApiResponse.error("Not found"));
        } catch (RateLimitException ex) {
            metrics.recordRequest(429);
            HttpUtil.writeJson(exchange, 429, gson, ApiResponse.error(ex.getMessage()));
        } catch (SecurityException ex) {
            metrics.recordRequest(401);
            auditLogger.log("request_denied", safeUsername(HttpUtil.authToken(exchange)), "denied", exchange.getRequestURI().getPath(), ex.getMessage());
            HttpUtil.writeJson(exchange, 401, gson, ApiResponse.error(ex.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            metrics.recordRequest(400);
            HttpUtil.writeJson(exchange, 400, gson, ApiResponse.error(ex.getMessage()));
        } catch (Exception ex) {
            metrics.recordError();
            metrics.recordRequest(500);
            com.auction.web.Logger.error("Unhandled exception in API handler", ex);
            HttpUtil.writeJson(exchange, 500, gson, ApiResponse.error("Internal server error"));
        }
    }

    private void handleUserSubroute(HttpExchange exchange, String path, String method, String token, String clientIp) throws IOException {
        String suffix = path.substring("/api/users/".length());
        if (suffix.isEmpty()) { HttpUtil.writeJson(exchange, 404, gson, ApiResponse.error("Not found")); return; }
        String[] parts = suffix.split("/");
        String username = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
        if (parts.length == 2 && "auction-limit".equals(parts[1]) && "PUT".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            AuctionLimitUpdateRequest request = HttpUtil.readBody(exchange, gson, AuctionLimitUpdateRequest.class);
            List<UserView> users = service.updateAuctionLimit(token, username, request.limit);
            auditLogger.log("user_limit_update", safeUsername(token), "success", username, String.valueOf(request.limit));
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(users));
            return;
        }
        if (parts.length == 1 && "DELETE".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            List<UserView> users = service.deleteUser(token, username);
            auditLogger.log("user_delete", safeUsername(token), "success", username, "admin_action");
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
            requireCsrf(exchange, token);
            AuctionUpdateRequest request = HttpUtil.readBody(exchange, gson, AuctionUpdateRequest.class);
            AuctionView auction = service.updateAuction(token, auctionId, request);
            auditLogger.log("auction_update", safeUsername(token), "success", auctionId, auction.getItemName());
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(auction));
            return;
        }
        if (parts.length == 1 && "DELETE".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            service.deleteAuction(token, auctionId);
            auditLogger.log("auction_delete", safeUsername(token), "success", auctionId, "item_removed");
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok("Deleted"));
            return;
        }
        if (parts.length == 2 && "bid".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            rateLimiter.check(clientIp + ":bid", 30, 60000, "Too many bids");
            BidRequest request = HttpUtil.readBody(exchange, gson, BidRequest.class);
            if (request.amount <= 0 || Double.isNaN(request.amount) || Double.isInfinite(request.amount)) { HttpUtil.writeJson(exchange, 400, gson, ApiResponse.error("Invalid bid amount")); return; }
            AuctionView auction = service.placeBid(token, auctionId, request.amount);
            metrics.recordBid();
            auditLogger.log("bid_place", safeUsername(token), "success", auctionId, String.valueOf(request.amount));
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(auction));
            return;
        }
        if (parts.length == 2 && "autobid".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            AutoBidRequest request = HttpUtil.readBody(exchange, gson, AutoBidRequest.class);
            AuctionView auction = service.configureAutoBid(token, auctionId, request.maxBid, request.increment);
            auditLogger.log("autobid_config", safeUsername(token), "success", auctionId, "max:" + request.maxBid);
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(auction));
            return;
        }
        if (parts.length == 2 && "cancel".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            AuctionView auction = service.cancelAuction(token, auctionId);
            auditLogger.log("auction_cancel", safeUsername(token), "success", auctionId, "admin_action");
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(auction));
            return;
        }
        if (parts.length == 2 && "pay".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            PaymentRequest request = HttpUtil.readBody(exchange, gson, PaymentRequest.class);
            Map<String, Object> result = service.payForAuction(token, auctionId, request.amount);
            auditLogger.log("payment_initiate", safeUsername(token), "success", auctionId, String.valueOf(request.amount));
            HttpUtil.writeJson(exchange, 200, gson, new ApiResponse(true, null, result));
            return;
        }
        if (parts.length == 2 && "buy-now".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            Map<String, Object> result = service.buyItNow(token, auctionId);
            auditLogger.log("buy_now", safeUsername(token), "success", auctionId, "");
            HttpUtil.writeJson(exchange, 200, gson, new ApiResponse(true, null, result));
            return;
        }
        if (parts.length == 2 && "refund".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            Map<String, String> body = HttpUtil.readBody(exchange, gson, Map.class);
            String reason = body.get("reason");
            Map<String, Object> result = service.requestRefund(token, auctionId, reason);
            auditLogger.log("refund_request", safeUsername(token), "success", auctionId, "");
            HttpUtil.writeJson(exchange, 200, gson, new ApiResponse(true, null, result));
            return;
        }
        if (parts.length == 2 && "schedule".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            Map<String, String> body = HttpUtil.readBody(exchange, gson, Map.class);
            long scheduledTime = Long.parseLong(body.get("scheduledStartTime"));
            AuctionView auction = service.scheduleAuction(token, auctionId, scheduledTime);
            auditLogger.log("auction_schedule", safeUsername(token), "success", auctionId, "");
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(auction));
            return;
        }
        if (parts.length == 2 && "dispute".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            DisputeRequest request = HttpUtil.readBody(exchange, gson, DisputeRequest.class);
            String result = service.reportDispute(token, auctionId, request.reason);
            auditLogger.log("dispute_report", safeUsername(token), "success", auctionId, "dispute");
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
            return;
        }
        if (parts.length == 2 && "mark-received".equals(parts[1]) && "POST".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            String result = service.markReceived(token, auctionId);
            auditLogger.log("mark_received", safeUsername(token), "success", auctionId, "fulfillment");
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
            return;
        }
        HttpUtil.writeJson(exchange, 404, gson, ApiResponse.error("Not found"));
    }

    private void handleAdminSubroute(HttpExchange exchange, String path, String method, String token, String clientIp) throws IOException {
        String suffix = path.substring("/api/admin/".length());
        String[] parts = suffix.split("/");
        if (parts.length == 1 && "refunds".equals(parts[0]) && "GET".equalsIgnoreCase(method)) {
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getRefunds(token)));
            return;
        }
        if (parts.length == 1 && "disputes".equals(parts[0]) && "GET".equalsIgnoreCase(method)) {
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getDisputes(token)));
            return;
        }
        if (parts.length == 2 && "disputes".equals(parts[0]) && "PUT".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            DisputeResolutionRequest request = HttpUtil.readBody(exchange, gson, DisputeResolutionRequest.class);
            String result = service.resolveDispute(token, parts[1], request.resolution, request.action);
            auditLogger.log("dispute_resolve", safeUsername(token), "success", parts[1], "admin_action");
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
            return;
        }
        if (parts.length == 2 && "payments".equals(parts[0]) && "PUT".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            PaymentConfirmRequest request = HttpUtil.readBody(exchange, gson, PaymentConfirmRequest.class);
            String result = service.confirmPayment(token, parts[1], request.status);
            auditLogger.log("payment_confirm", safeUsername(token), "success", parts[1], request.status);
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
            return;
        }
        if (parts.length == 2 && "refunds".equals(parts[0]) && "PUT".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            Map<String, String> body = HttpUtil.readBody(exchange, gson, Map.class);
            String action = body.get("action");
            String result = service.processRefund(token, parts[1], action);
            auditLogger.log("refund_process", safeUsername(token), "success", parts[1], action);
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
            return;
        }
        if (parts.length == 1 && "backup".equals(parts[0]) && "POST".equalsIgnoreCase(method)) {
            requireCsrf(exchange, token);
            String result = service.exportBackup(token);
            auditLogger.log("backup", safeUsername(token), "success", "admin", "database_export");
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(result));
            return;
        }
        if (parts.length == 1 && "metrics".equals(parts[0]) && "GET".equalsIgnoreCase(method)) {
            requireRole(token, com.auction.web.model.User.Role.ADMIN);
            HttpUtil.writeJson(exchange, 200, gson, ApiResponse.ok(service.getSystemMetrics(token)));
            return;
        }
        HttpUtil.writeJson(exchange, 404, gson, ApiResponse.error("Not found"));
    }

    private void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "http://localhost:8080");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-CSRF-Token");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
    }

    private void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");
        exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self' ws: wss:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
    }

    private String safeUsername(String token) {
        try { return service.requireSession(token).getUsername(); } catch (Exception ignored) { return "anonymous"; }
    }

    private void requireCsrf(HttpExchange exchange, String token) {
        String csrfHeader = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
        if (csrfHeader == null || !service.validateCsrfToken(token, csrfHeader)) throw new SecurityException("Invalid or missing CSRF token");
    }

    private void requireRole(String token, com.auction.web.model.User.Role role) {
        com.auction.web.model.User user = service.requireUser(token);
        if (user.getRole() != role) throw new SecurityException("Unauthorized");
    }

    private void validateCaptcha(String challengeId, Integer answer) {
        if (challengeId == null || answer == null) throw new IllegalArgumentException("CAPTCHA challenge required");
        CaptchaChallenge challenge = captchaStore.remove(challengeId);
        if (challenge == null) throw new IllegalArgumentException("CAPTCHA expired or invalid");
        if (!challenge.isValid()) throw new IllegalArgumentException("CAPTCHA expired");
        if (challenge.answer != answer) throw new IllegalArgumentException("CAPTCHA answer is incorrect");
    }

    public void shutdown() {
        rateLimiter.shutdown();
        auditLogger.shutdown();
    }

    private String detectImageType(byte[] bytes) {
        if (bytes.length < 4) return null;
        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) return "jpg";
        if (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "png";
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "gif";
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') return "webp";
        return null;
    }
}
