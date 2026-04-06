package com.auction.web.service;

import com.auction.web.Logger;
import com.auction.web.dto.AuctionCreateRequest;
import com.auction.web.dto.AuctionUpdateRequest;
import com.auction.web.dto.AuctionView;
import com.auction.web.dto.SessionView;
import com.auction.web.dto.UserView;
import com.auction.web.model.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class AuctionService {
    private DatabaseManager db;
    private final WebSocketServerImpl wsServer = new WebSocketServerImpl(8889);
    private final List<String> eventQueue = new CopyOnWriteArrayList<>();
    private EmailService emailService;
    private ZaloPayService zaloPayService;
    private String baseUrl = "http://localhost:8080";

    public void setEmailService(EmailService emailService) { this.emailService = emailService; }
    public void setZaloPayService(ZaloPayService zaloPayService) { this.zaloPayService = zaloPayService; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public AuctionService() {
        this(Boolean.getBoolean("test.db"));
    }

    public AuctionService(boolean testMode) {
        this(testMode, null, null, null);
    }

    public AuctionService(boolean testMode, String pgUrl, String pgUser, String pgPass) {
        System.out.println("AuctionService initialized. Using persistent sessions.");
        db = new DatabaseManager(testMode, pgUrl, pgUser, pgPass);
        wsServer.setTokenValidator(token -> {
            try { requireUser(token); return true; } catch (Exception e) { return false; }
        });
        if (!wsServer.tryStart()) {
            System.err.println("WARNING: Continuing without WebSocket server. Live updates will be unavailable.");
        }
        seedDataIfEmpty();
        startAuctionMonitor();
    }

    private void broadcast(String message) {
        String timedMessage = message + ":" + System.currentTimeMillis();
        eventQueue.add(timedMessage);
        if (eventQueue.size() > 100) eventQueue.remove(0);
        wsServer.broadcastMessage(timedMessage);
    }

    public List<String> getEvents(long since) {
        return eventQueue.stream()
            .filter(e -> {
                int idx = e.lastIndexOf(':');
                return idx > 0 && Long.parseLong(e.substring(idx + 1)) > since;
            })
            .toList();
    }

    // --- AUTH ---

    public SessionView login(String username, String password) {
        User user = findUserByUsername(username);
        if (user == null) throw new IllegalArgumentException("Invalid credentials");
        if (user.isLocked()) throw new IllegalStateException("Account is locked. Try again in " + ((user.getLockedUntil() - System.currentTimeMillis()) / 1000) + " seconds");
        if (!user.checkPassword(password)) {
            user.recordFailedLogin();
            updateUser(user);
            throw new IllegalArgumentException("Invalid credentials");
        }
        if (user.isTotpEnabled() && user.getTotpSecret() != null) {
            throw new IllegalStateException("2FA_REQUIRED");
        }
        user.resetFailedAttempts();
        updateUser(user);
        String token = UUID.randomUUID().toString();
        String csrfToken = UUID.randomUUID().toString();
        saveSessionToDb(token, username, csrfToken);
        SessionView view = toSessionView(token, user);
        view.setCsrfToken(csrfToken);
        return view;
    }

    private void saveSessionToDb(String token, String username, String csrfToken) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO sessions (token, username, created_at, last_active_at, csrf_token) VALUES (?, ?, ?, ?, ?)")) {
            long now = System.currentTimeMillis();
            ps.setString(1, token); ps.setString(2, username);
            ps.setLong(3, now); ps.setLong(4, now); ps.setString(5, csrfToken);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to create session", e); }
    }

    private String getUsernameFromSession(String token) {
        if (token == null) return null;
        long sessionTtlSeconds = 86400;
        long idleTimeoutSeconds = 1800;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT username, last_active_at, created_at FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                long lastActive = rs.getLong("last_active_at");
                long now = System.currentTimeMillis();
                long created = rs.getLong("created_at");
                if (now - created > sessionTtlSeconds * 1000L) {
                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM sessions WHERE token = ?")) { del.setString(1, token); del.executeUpdate(); }
                    return null;
                }
                if (now - lastActive > idleTimeoutSeconds * 1000L) {
                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM sessions WHERE token = ?")) { del.setString(1, token); del.executeUpdate(); }
                    return null;
                }
                try (PreparedStatement update = conn.prepareStatement("UPDATE sessions SET last_active_at = ? WHERE token = ?")) { update.setLong(1, now); update.setString(2, token); update.executeUpdate(); }
                return rs.getString("username");
            }
        } catch (SQLException e) { throw new RuntimeException("Database error reading session", e); }
        return null;
    }

    public SessionView register(String username, String password, String role) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Username is required");
        if (username.length() > 64) throw new IllegalArgumentException("Username must be 64 characters or less");
        if (!User.isValidPassword(password)) throw new IllegalArgumentException(User.getPasswordPolicyMessage());
        String id = UUID.randomUUID().toString();
        User.Role userRole = User.Role.USER;
        if (role != null) {
            try { userRole = User.Role.valueOf(role.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }
        User user = new User(id, username, password, userRole);
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO users (id, username, password, role, auction_limit, created_at, email, email_verified, failed_login_attempts, locked_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, user.getId()); ps.setString(2, user.getUsername());
            ps.setString(3, user.getPasswordHashForStorage()); ps.setString(4, user.getRole().name());
            ps.setInt(5, user.getAuctionLimit()); ps.setLong(6, user.getCreatedAt());
            ps.setString(7, null); ps.setBoolean(8, false); ps.setInt(9, 0); ps.setLong(10, 0);
            ps.executeUpdate();
            return login(username, password);
        } catch (SQLException e) { throw new IllegalArgumentException("Username already exists or database error"); }
    }

    public SessionView requireSession(String token) {
        User user = requireUser(token);
        return toSessionView(token, user);
    }

    public User requireUser(String token) {
        String username = getUsernameFromSession(token);
        if (username == null) throw new SecurityException("Unauthorized");
        User user = findUserByUsername(username);
        if (user == null) throw new SecurityException("User not found");
        return user;
    }

    public User requireRole(String token, User.Role role) {
        User user = requireUser(token);
        if (user.getRole() != role) throw new SecurityException("Unauthorized");
        return user;
    }

    public boolean validateCsrfToken(String token, String csrfToken) {
        if (token == null || csrfToken == null || csrfToken.isBlank()) return false;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT csrf_token FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return csrfToken.equals(rs.getString("csrf_token"));
        } catch (SQLException e) { throw new RuntimeException("Database error validating CSRF", e); }
        return false;
    }

    // --- PASSWORD RESET ---

    public String requestPasswordReset(String username) {
        User user = findUserByUsername(username);
        if (user == null) return "If an account exists with that username, a reset token has been generated";
        String resetToken = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + 3600000L;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO password_reset_tokens (token, username, expires_at, used) VALUES (?, ?, ?, FALSE)")) {
            ps.setString(1, resetToken); ps.setString(2, username); ps.setLong(3, expiresAt);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to create reset token", e); }
        if (emailService != null && user.getEmail() != null && !user.getEmail().isBlank()) {
            emailService.sendPasswordReset(user.getEmail(), username, "http://localhost:8080/reset?token=" + resetToken);
        }
        return "If an account exists with that username, a reset token has been generated";
    }

    public SessionView confirmPasswordReset(String token, String newPassword) {
        if (!User.isValidPassword(newPassword)) throw new IllegalArgumentException(User.getPasswordPolicyMessage());
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String username;
                try (PreparedStatement ps = conn.prepareStatement("SELECT username FROM password_reset_tokens WHERE token = ? AND used = FALSE AND expires_at > ?")) {
                    ps.setString(1, token); ps.setLong(2, System.currentTimeMillis());
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new IllegalArgumentException("Invalid or expired reset token");
                    username = rs.getString("username");
                }
                User user = findUserByUsername(username);
                if (user == null) throw new IllegalArgumentException("User not found");
                user.setPassword(newPassword);
                updateUser(user);
                try (PreparedStatement ps = conn.prepareStatement("UPDATE password_reset_tokens SET used = TRUE WHERE token = ?")) { ps.setString(1, token); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE username = ?")) { ps.setString(1, username); ps.executeUpdate(); }
                conn.commit();
                return login(username, newPassword);
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Password reset failed", e); }
    }

    // --- EMAIL VERIFICATION ---

    public String requestEmailVerification(String token, String email) {
        User user = requireUser(token);
        if (email == null || email.isBlank() || email.length() > 255) throw new IllegalArgumentException("Valid email is required");
        if (!email.matches("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$")) throw new IllegalArgumentException("Invalid email format");
        user.setEmail(email);
        user.setEmailVerified(false);
        updateUser(user);
        String verifyToken = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + 86400000L;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO verification_tokens (token, username, email, expires_at, used) VALUES (?, ?, ?, ?, FALSE)")) {
            ps.setString(1, verifyToken); ps.setString(2, user.getUsername());
            ps.setString(3, email); ps.setLong(4, expiresAt); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to create verification token", e); }
        return "Verification email sent. Token: " + verifyToken;
    }

    public SessionView confirmEmailVerification(String token) {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String username;
                try (PreparedStatement ps = conn.prepareStatement("SELECT username FROM verification_tokens WHERE token = ? AND used = FALSE AND expires_at > ?")) {
                    ps.setString(1, token); ps.setLong(2, System.currentTimeMillis());
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new IllegalArgumentException("Invalid or expired verification token");
                    username = rs.getString("username");
                }
                User user = findUserByUsername(username);
                if (user == null) throw new IllegalArgumentException("User not found");
                user.setEmailVerified(true);
                updateUser(user);
                try (PreparedStatement ps = conn.prepareStatement("UPDATE verification_tokens SET used = TRUE WHERE token = ?")) { ps.setString(1, token); ps.executeUpdate(); }
                conn.commit();
                String sessionToken = UUID.randomUUID().toString();
                String csrfToken = UUID.randomUUID().toString();
                saveSessionToDb(sessionToken, username, csrfToken);
                SessionView view = toSessionView(sessionToken, user);
                view.setCsrfToken(csrfToken);
                return view;
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Email verification failed", e); }
    }

    // --- ACCOUNT MANAGEMENT ---

    public SessionView changePassword(String token, String currentPassword, String newPassword) {
        User user = requireUser(token);
        if (!user.checkPassword(currentPassword)) throw new IllegalArgumentException("Incorrect current password");
        if (!User.isValidPassword(newPassword)) throw new IllegalArgumentException(User.getPasswordPolicyMessage());
        user.setPassword(newPassword);
        updateUser(user);
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE token != ? AND username = ?")) {
            ps.setString(1, token); ps.setString(2, user.getUsername());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to invalidate sessions", e); }
        return toSessionView(token, user);
    }

    public SessionView updateUsername(String token, String newUsername) {
        User user = requireUser(token);
        if (newUsername == null || newUsername.isBlank()) throw new IllegalArgumentException("Username is required");
        String oldUsername = user.getUsername();
        user.setUsername(newUsername);
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET username = ? WHERE id = ?")) { ps.setString(1, newUsername); ps.setString(2, user.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE sessions SET username = ? WHERE token = ?")) { ps.setString(1, newUsername); ps.setString(2, token); ps.executeUpdate(); }
                conn.commit();
                return toSessionView(token, user);
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { user.setUsername(oldUsername); throw new IllegalArgumentException("Username already taken"); }
    }

    public String deleteAccount(String token, String password) {
        User user = requireUser(token);
        if (!user.checkPassword(password)) throw new IllegalArgumentException("Incorrect password");
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM autobids WHERE bidder_id = ?")) { ps.setString(1, user.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM bids WHERE bidder_id = ?")) { ps.setString(1, user.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM auctions WHERE owner_id = ?")) {
                    ps.setString(1, user.getId());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        String auctionId = rs.getString("id");
                        try (PreparedStatement ps2 = conn.prepareStatement("DELETE FROM autobids WHERE auction_id = ?")) { ps2.setString(1, auctionId); ps2.executeUpdate(); }
                        try (PreparedStatement ps2 = conn.prepareStatement("DELETE FROM bids WHERE auction_id = ?")) { ps2.setString(1, auctionId); ps2.executeUpdate(); }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM auctions WHERE owner_id = ?")) { ps.setString(1, user.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist WHERE user_id = ?")) { ps.setString(1, user.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM notifications WHERE user_id = ?")) { ps.setString(1, user.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM payments WHERE buyer_id = ?")) { ps.setString(1, user.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM disputes WHERE reporter_id = ?")) { ps.setString(1, user.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM invoices WHERE buyer_id = ?")) { ps.setString(1, user.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) { ps.setString(1, user.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE username = ?")) { ps.setString(1, user.getUsername()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM password_reset_tokens WHERE username = ?")) { ps.setString(1, user.getUsername()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM verification_tokens WHERE username = ?")) { ps.setString(1, user.getUsername()); ps.executeUpdate(); }
                conn.commit();
                return "Account deleted";
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Delete failed"); }
    }

    // --- ADMIN ---

    public List<UserView> updateAuctionLimit(String token, String targetUsername, int newLimit) {
        requireRole(token, User.Role.ADMIN);
        if (newLimit < 0) throw new IllegalArgumentException("Auction limit cannot be negative");
        User target = findUserByUsername(targetUsername);
        if (target == null) throw new IllegalArgumentException("User not found");
        target.setAuctionLimit(newLimit);
        updateUser(target);
        return getUsers(token);
    }

    public List<UserView> deleteUser(String token, String targetUsername) {
        requireRole(token, User.Role.ADMIN);
        User target = findUserByUsername(targetUsername);
        if (target == null) throw new IllegalArgumentException("User not found");
        if (target.getRole() == User.Role.ADMIN) throw new IllegalArgumentException("Cannot delete admin");
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM autobids WHERE bidder_id = ?")) { ps.setString(1, target.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM bids WHERE bidder_id = ?")) { ps.setString(1, target.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM auctions WHERE owner_id = ?")) {
                    ps.setString(1, target.getId());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        String auctionId = rs.getString("id");
                        try (PreparedStatement ps2 = conn.prepareStatement("DELETE FROM autobids WHERE auction_id = ?")) { ps2.setString(1, auctionId); ps2.executeUpdate(); }
                        try (PreparedStatement ps2 = conn.prepareStatement("DELETE FROM bids WHERE auction_id = ?")) { ps2.setString(1, auctionId); ps2.executeUpdate(); }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM auctions WHERE owner_id = ?")) { ps.setString(1, target.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist WHERE user_id = ?")) { ps.setString(1, target.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM notifications WHERE user_id = ?")) { ps.setString(1, target.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM payments WHERE buyer_id = ?")) { ps.setString(1, target.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM disputes WHERE reporter_id = ?")) { ps.setString(1, target.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM invoices WHERE buyer_id = ?")) { ps.setString(1, target.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) { ps.setString(1, target.getId()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE username = ?")) { ps.setString(1, target.getUsername()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM password_reset_tokens WHERE username = ?")) { ps.setString(1, target.getUsername()); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM verification_tokens WHERE username = ?")) { ps.setString(1, target.getUsername()); ps.executeUpdate(); }
                conn.commit();
                return getUsers(token);
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Delete failed"); }
    }

    // --- AUCTIONS ---

    public List<AuctionView> getAuctions() {
        List<AuctionView> views = new ArrayList<>();
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM auctions ORDER BY end_time ASC")) {
            Map<String, List<BidTransaction>> allBids = new HashMap<>();
            try (Statement bidStmt = conn.createStatement();
                 ResultSet bidRs = bidStmt.executeQuery("SELECT * FROM bids ORDER BY timestamp ASC")) {
                while (bidRs.next()) {
                    String auctionId = bidRs.getString("auction_id");
                    allBids.computeIfAbsent(auctionId, k -> new ArrayList<>())
                           .add(new BidTransaction(bidRs.getString("bidder_id"), bidRs.getString("bidder_username"), bidRs.getDouble("amount"), bidRs.getLong("timestamp")));
                }
            }
            while (rs.next()) {
                Auction auction = mapResultSetToAuctionWithoutBids(rs);
                auction.getBidHistory().addAll(allBids.getOrDefault(auction.getId(), Collections.emptyList()));
                views.add(new AuctionView(auction));
            }
        } catch (SQLException e) { throw new RuntimeException("Failed to load auctions", e); }
        return views;
    }

    public AuctionView getAuction(String id) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM auctions WHERE id = ?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return new AuctionView(mapResultSetToAuction(rs, conn));
        } catch (SQLException e) { throw new RuntimeException("Database error loading auction", e); }
        throw new IllegalArgumentException("Auction not found");
    }

    public List<UserView> getUsers(String token) {
        requireRole(token, User.Role.ADMIN);
        List<UserView> users = new ArrayList<>();
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users ORDER BY username")) {
            while (rs.next()) users.add(toUserView(mapResultSetToUser(rs)));
        } catch (SQLException e) { throw new RuntimeException("Failed to load users", e); }
        return users;
    }

    public AuctionView createAuction(String token, AuctionCreateRequest request) {
        User seller = requireUser(token);
        if (request.name == null || request.name.isBlank()) throw new IllegalArgumentException("Auction name is required");
        if (request.name.length() > 500) throw new IllegalArgumentException("Auction name must be 500 characters or less");
        if (request.startingPrice <= 0) throw new IllegalArgumentException("Starting price must be positive");
        if (request.startingPrice > 1e15) throw new IllegalArgumentException("Starting price is too large");
        if (request.durationMinutes <= 0) throw new IllegalArgumentException("Duration must be positive");
        if (request.durationMinutes > 525600) throw new IllegalArgumentException("Duration must not exceed 1 year");
        if (request.itemType == null || request.itemType.isBlank()) throw new IllegalArgumentException("Item type is required");
        if (request.buyItNowPrice < 0) throw new IllegalArgumentException("Buy-it-now price must be non-negative");
        if (request.reservePrice < 0) throw new IllegalArgumentException("Reserve price must be non-negative");
        if (request.buyItNowPrice > 0 && request.buyItNowPrice <= request.startingPrice) throw new IllegalArgumentException("Buy-it-now price must be greater than starting price");
        if (request.reservePrice > 0 && request.reservePrice <= request.startingPrice) throw new IllegalArgumentException("Reserve price must be greater than starting price");
        long count = getActiveAuctionCount(seller.getId());
        if (seller.getRole() != User.Role.ADMIN && count >= seller.getAuctionLimit()) {
            throw new IllegalStateException("Auction limit reached (" + seller.getAuctionLimit() + ")");
        }
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long startTime = request.scheduledStartTime > now ? request.scheduledStartTime : now;
        String sanitizedName = com.auction.web.http.HtmlSanitizer.sanitizeAuctionName(request.name);
        String sanitizedDesc = com.auction.web.http.HtmlSanitizer.sanitize(request.description);
        String sanitizedExtra = com.auction.web.http.HtmlSanitizer.sanitize(request.extraInfo);
        Item item = ItemFactory.createItem(request.itemType, UUID.randomUUID().toString(), sanitizedName, sanitizedDesc, request.startingPrice, sanitizedExtra);
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO auctions (id, item_id, item_type, item_name, description, starting_price, extra_info, owner_id, owner_username, start_time, end_time, current_price, image_filename, currency, state, buy_it_now_price, reserve_price) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, item.getId()); ps.setString(3, item.getType());
            ps.setString(4, item.getName()); ps.setString(5, item.getDescription()); ps.setDouble(6, item.getStartingPrice());
            ps.setString(7, item.getExtraValue()); ps.setString(8, seller.getId()); ps.setString(9, seller.getUsername());
            ps.setLong(10, startTime); ps.setLong(11, startTime + ((long) request.durationMinutes * 60_000L));
            ps.setDouble(12, item.getStartingPrice()); ps.setString(13, request.imageFilename == null ? "" : request.imageFilename);
            ps.setString(14, request.currency == null || request.currency.isBlank() ? "USD" : request.currency.toUpperCase());
            ps.setString(15, startTime > now ? "OPEN" : "RUNNING");
            ps.setDouble(16, request.buyItNowPrice); ps.setDouble(17, request.reservePrice);
            ps.executeUpdate();
            broadcast("AUCTION_ADDED:" + id);
            return getAuction(id);
        } catch (SQLException e) { throw new RuntimeException("Failed to create auction", e); }
    }

    public AuctionView updateAuction(String token, String auctionId, AuctionUpdateRequest request) {
        User seller = requireUser(token);
        try (Connection conn = db.getConnection()) {
            Auction auction = getAuctionEntity(conn, auctionId);
            if (!auction.getOwnerId().equals(seller.getId()) && seller.getRole() != User.Role.ADMIN) throw new SecurityException("Unauthorized");
            if (request.name == null || request.name.isBlank()) throw new IllegalArgumentException("Auction name is required");
            if (request.startingPrice <= 0) throw new IllegalArgumentException("Starting price must be positive");
            if (request.durationMinutes <= 0) throw new IllegalArgumentException("Duration must be positive");
            if (request.durationMinutes > 525600) throw new IllegalArgumentException("Duration must not exceed 1 year");
            if (request.itemType == null || request.itemType.isBlank()) throw new IllegalArgumentException("Item type is required");
            if (auction.hasBids()) throw new IllegalStateException("Cannot edit with bids");
            String sanitizedName = com.auction.web.http.HtmlSanitizer.sanitizeAuctionName(request.name);
            String sanitizedDesc = com.auction.web.http.HtmlSanitizer.sanitize(request.description);
            String sanitizedExtra = com.auction.web.http.HtmlSanitizer.sanitize(request.extraInfo);
            Item item = ItemFactory.createItem(request.itemType, auction.getItem().getId(), sanitizedName, sanitizedDesc, request.startingPrice, sanitizedExtra);
            long newEndTime = System.currentTimeMillis() + ((long) request.durationMinutes * 60_000L);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET item_id=?, item_type=?, item_name=?, description=?, starting_price=?, extra_info=?, currency=?, end_time=? WHERE id=?")) {
                ps.setString(1, item.getId()); ps.setString(2, item.getType()); ps.setString(3, item.getName());
                ps.setString(4, item.getDescription()); ps.setDouble(5, item.getStartingPrice());
                ps.setString(6, item.getExtraValue());
                ps.setString(7, request.currency == null || request.currency.isBlank() ? "USD" : request.currency.toUpperCase());
                ps.setLong(8, newEndTime); ps.setString(9, auctionId);
                ps.executeUpdate();
            }
            broadcast("AUCTION_UPDATED:" + auctionId);
            return getAuction(auctionId);
        } catch (SQLException e) { throw new RuntimeException("Update failed", e); }
    }

    public void deleteAuction(String token, String auctionId) {
        User seller = requireUser(token);
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Auction auction = getAuctionEntity(conn, auctionId);
                if (!auction.getOwnerId().equals(seller.getId()) && seller.getRole() != User.Role.ADMIN) throw new SecurityException("Unauthorized");
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM autobids WHERE auction_id = ?")) { ps.setString(1, auctionId); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM bids WHERE auction_id = ?")) { ps.setString(1, auctionId); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist WHERE auction_id = ?")) { ps.setString(1, auctionId); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM payments WHERE auction_id = ?")) { ps.setString(1, auctionId); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM disputes WHERE auction_id = ?")) { ps.setString(1, auctionId); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM invoices WHERE auction_id = ?")) { ps.setString(1, auctionId); ps.executeUpdate(); }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM auctions WHERE id = ?")) { ps.setString(1, auctionId); ps.executeUpdate(); }
                conn.commit();
                broadcast("AUCTION_DELETED:" + auctionId);
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Delete failed", e); }
    }

    public AuctionView cancelAuction(String token, String auctionId) {
        requireRole(token, User.Role.ADMIN);
        try (Connection conn = db.getConnection()) {
            Auction auction = getAuctionEntity(conn, auctionId);
            if (auction.getState() == Auction.State.FINISHED) throw new IllegalStateException("Cannot cancel a finished auction");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET state = 'CANCELED' WHERE id = ?")) {
                ps.setString(1, auctionId);
                int rows = ps.executeUpdate();
                if (rows == 0) throw new IllegalArgumentException("Auction not found");
                broadcast("AUCTION_STATE_CHANGED:" + auctionId);
                return getAuction(auctionId);
            }
        } catch (SQLException e) { throw new RuntimeException("Cancel failed", e); }
    }

    // --- BIDDING ---

    public AuctionView placeBid(String token, String auctionId, double amount) {
        User bidder = requireUser(token);
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Auction auction = getAuctionEntity(conn, auctionId);
                if (auction.placeBid(bidder, amount)) {
                    updateAuctionState(conn, auction);
                    saveBid(conn, auctionId, bidder, amount);
                    evaluateAutoBids(conn, auctionId);
                    conn.commit();
                    broadcast("BID_PLACED:" + auctionId);
                    return getAuction(auctionId);
                }
                if (auction.getState() == Auction.State.FINISHED) throw new IllegalStateException("Auction has ended");
                if (bidder.getId().equals(auction.getOwnerId())) throw new IllegalStateException("Cannot bid on your own auction");
                if (bidder.getId().equals(auction.getHighestBidderId())) throw new IllegalStateException("Cannot bid against yourself");
                if (amount <= auction.getCurrentPrice()) throw new IllegalStateException("Bid must be higher than current price of " + auction.getCurrentPrice());
                throw new IllegalStateException("Bid was rejected");
            } catch (Exception e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Bid failed", e); }
    }

    public AuctionView configureAutoBid(String token, String auctionId, double maxBid, double increment) {
        User bidder = requireUser(token);
        if (maxBid <= 0 || Double.isNaN(maxBid) || Double.isInfinite(maxBid)) throw new IllegalArgumentException("Invalid max bid");
        if (increment <= 0 || Double.isNaN(increment) || Double.isInfinite(increment)) throw new IllegalArgumentException("Invalid increment");
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("MERGE INTO autobids (auction_id, bidder_id, bidder_username, max_bid, increment) KEY(auction_id, bidder_id) VALUES (?,?,?,?,?)")) {
                    ps.setString(1, auctionId); ps.setString(2, bidder.getId());
                    ps.setString(3, bidder.getUsername()); ps.setDouble(4, maxBid); ps.setDouble(5, increment);
                    ps.executeUpdate();
                }
                evaluateAutoBids(conn, auctionId);
                conn.commit();
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Auto-bid setup failed", e); }
        broadcast("AUTO_BID_CONFIGURED:" + auctionId);
        return getAuction(auctionId);
    }

    private void evaluateAutoBids(Connection conn, String auctionId) {
        try {
            boolean changed = true;
            int iterations = 0;
            while (changed && iterations < 100) {
                changed = false;
                iterations++;
                Auction auction = getAuctionEntity(conn, auctionId);
                if (auction.getState() != Auction.State.RUNNING) break;
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM autobids WHERE auction_id = ? AND bidder_id <> ? ORDER BY max_bid DESC, increment ASC")) {
                    ps.setString(1, auctionId);
                    ps.setString(2, auction.getHighestBidderId() == null ? "" : auction.getHighestBidderId());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        double maxBid = rs.getDouble("max_bid");
                        double increment = rs.getDouble("increment");
                        double nextBid = auction.getCurrentPrice() + increment;
                        if (nextBid <= maxBid) {
                            User autoBidder = User.fromStoredHash(rs.getString("bidder_id"), rs.getString("bidder_username"), "", User.Role.USER);
                            if (auction.placeBid(autoBidder, nextBid)) {
                                updateAuctionState(conn, auction);
                                saveBid(conn, auctionId, autoBidder, nextBid);
                                changed = true;
                            }
                        }
                    }
                }
            }
            if (iterations >= 100) System.err.println("WARNING: Auto-bid iteration limit reached for auction " + auctionId);
        } catch (SQLException e) { throw new RuntimeException("Auto-bid evaluation failed", e); }
    }

    // --- MARKETPLACE: PAYMENTS, NOTIFICATIONS, DISPUTES ---

    public Map<String, Object> payForAuction(String token, String auctionId, double amount) {
        User buyer = requireUser(token);
        String paymentId = UUID.randomUUID().toString();
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Auction auction = getAuctionEntity(conn, auctionId);
                if (!buyer.getId().equals(auction.getHighestBidderId())) throw new IllegalStateException("You are not the winner");
                if (auction.getState() != Auction.State.FINISHED) throw new IllegalStateException("Auction is not finished");
                if (amount < auction.getCurrentPrice()) throw new IllegalArgumentException("Payment amount must be at least " + auction.getCurrentPrice());
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO payments (id, auction_id, buyer_id, buyer_username, amount, status, created_at) VALUES (?, ?, ?, ?, ?, 'pending', ?)")) {
                    ps.setString(1, paymentId); ps.setString(2, auctionId);
                    ps.setString(3, buyer.getId()); ps.setString(4, buyer.getUsername());
                    ps.setDouble(5, amount); ps.setLong(6, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET fulfillment_status = 'awaiting_payment' WHERE id = ?")) {
                    ps.setString(1, auctionId); ps.executeUpdate();
                }
                conn.commit();
                addNotification(auction.getOwnerId(), "PAYMENT", buyer.getUsername() + " initiated payment for " + auction.getItem().getName());
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Payment failed", e); }
        long amountVnd = (long) (amount * 25000);
        String appTransId = paymentId.replace("-", "").substring(0, 32);
        String description = "Thanh toan don hang #" + auctionId;
        String redirectUrl = baseUrl + "/payment-result";
        Map<String, Object> zaloPayResult = zaloPayService != null && zaloPayService.isConfigured()
            ? zaloPayService.createOrder(appTransId, amountVnd, description, buyer.getUsername(), redirectUrl)
            : zaloPayStubCreateOrder(appTransId, amountVnd);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("paymentId", paymentId);
        response.put("orderUrl", zaloPayResult.get("order_url"));
        response.put("orderToken", zaloPayResult.get("order_token"));
        response.put("qrCode", zaloPayResult.get("qr_code"));
        response.put("amountVnd", amountVnd);
        response.put("message", "Please complete payment via ZaloPay");
        return response;
    }

    private Map<String, Object> zaloPayStubCreateOrder(String appTransId, long amountVnd) {
        Map<String, Object> stub = new LinkedHashMap<>();
        stub.put("return_code", 1);
        stub.put("zp_trans_token", "stub_token_" + System.currentTimeMillis());
        stub.put("order_url", "https://sb-openapi.zalopay.vn/stub?trans_id=" + appTransId);
        stub.put("order_token", "stub_order_token");
        stub.put("qr_code", "");
        return stub;
    }

    public String confirmPayment(String token, String paymentId, String status) {
        requireRole(token, User.Role.ADMIN);
        if (!Set.of("pending", "completed", "failed", "refunded").contains(status)) throw new IllegalArgumentException("Invalid payment status");
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String auctionId, buyerId;
                try (PreparedStatement ps = conn.prepareStatement("SELECT auction_id, buyer_id FROM payments WHERE id = ?")) {
                    ps.setString(1, paymentId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new IllegalArgumentException("Payment not found");
                    auctionId = rs.getString("auction_id");
                    buyerId = rs.getString("buyer_id");
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE payments SET status = ? WHERE id = ?")) {
                    ps.setString(1, status); ps.setString(2, paymentId);
                    ps.executeUpdate();
                }
                if ("completed".equals(status)) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET fulfillment_status = 'paid' WHERE id = ?")) {
                        ps.setString(1, auctionId); ps.executeUpdate();
                    }
                    generateInvoice(conn, paymentId, auctionId, buyerId);
                    addNotification(buyerId, "PAYMENT", "Your payment has been confirmed");
                    try (PreparedStatement ps = conn.prepareStatement("SELECT owner_id FROM auctions WHERE id = ?")) {
                        ps.setString(1, auctionId);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            addNotification(rs.getString("owner_id"), "PAYMENT", "Payment confirmed for auction " + auctionId);
                        }
                    }
                }
                conn.commit();
                return "Payment " + status;
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Payment confirmation failed", e); }
    }

    public Map<String, Object> confirmPaymentFromCallback(String appTransId, String zpTransId, long amount, int status) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String paymentId = null;
                try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM payments WHERE id LIKE ?")) {
                    String pattern = appTransId.substring(0, Math.min(8, appTransId.length())) + "%";
                    ps.setString(1, pattern);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        String candidate = rs.getString("id");
                        if (candidate.replace("-", "").startsWith(appTransId)) {
                            paymentId = candidate;
                            break;
                        }
                    }
                }
                if (paymentId == null) {
                    result.put("return_code", 0);
                    result.put("return_message", "Payment not found for appTransId: " + appTransId);
                    return result;
                }
                String auctionId, buyerId;
                try (PreparedStatement ps = conn.prepareStatement("SELECT auction_id, buyer_id, status FROM payments WHERE id = ?")) {
                    ps.setString(1, paymentId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) {
                        result.put("return_code", 0);
                        result.put("return_message", "Payment not found");
                        return result;
                    }
                    auctionId = rs.getString("auction_id");
                    buyerId = rs.getString("buyer_id");
                    String currentStatus = rs.getString("status");
                    if ("completed".equals(currentStatus)) {
                        result.put("return_code", 1);
                        result.put("return_message", "Already processed");
                        return result;
                    }
                }
                if (status == 1) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE payments SET status = 'completed' WHERE id = ?")) {
                        ps.setString(1, paymentId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET fulfillment_status = 'paid' WHERE id = ?")) {
                        ps.setString(1, auctionId); ps.executeUpdate();
                    }
                    generateInvoice(conn, paymentId, auctionId, buyerId);
                    addNotification(buyerId, "PAYMENT", "Your payment has been confirmed via ZaloPay");
                    try (PreparedStatement ps = conn.prepareStatement("SELECT owner_id FROM auctions WHERE id = ?")) {
                        ps.setString(1, auctionId);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            addNotification(rs.getString("owner_id"), "PAYMENT", "Payment confirmed for auction " + auctionId);
                        }
                    }
                    Logger.info("Payment completed via ZaloPay IPN: paymentId=" + paymentId + " zpTransId=" + zpTransId);
                    result.put("return_code", 1);
                    result.put("return_message", "Payment confirmed");
                } else {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE payments SET status = 'failed' WHERE id = ?")) {
                        ps.setString(1, paymentId);
                        ps.executeUpdate();
                    }
                    addNotification(buyerId, "PAYMENT", "Your payment failed");
                    Logger.warn("Payment failed via ZaloPay IPN: paymentId=" + paymentId);
                    result.put("return_code", 1);
                    result.put("return_message", "Payment marked as failed");
                }
                conn.commit();
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) {
            Logger.error("Failed to process ZaloPay callback", e);
            result.put("return_code", 0);
            result.put("return_message", "Internal error: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> queryPaymentStatus(String paymentId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = db.getConnection()) {
            String appTransId = paymentId.replace("-", "").substring(0, 32);
            Map<String, Object> zaloPayResult = zaloPayService != null && zaloPayService.isConfigured()
                ? zaloPayService.queryOrder(appTransId)
                : Map.of("return_code", 1, "status", 1);
            int zpStatus = ((Number) zaloPayResult.getOrDefault("status", 0)).intValue();
            String dbStatus = "pending";
            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM payments WHERE id = ?")) {
                ps.setString(1, paymentId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    dbStatus = rs.getString("status");
                }
            }
            result.put("paymentId", paymentId);
            result.put("dbStatus", dbStatus);
            result.put("zalopayStatus", zpStatus);
            result.put("zalopayMessage", zaloPayResult.get("return_message"));
            if ("pending".equals(dbStatus) && zpStatus == 1) {
                confirmPaymentFromCallback(appTransId, String.valueOf(zaloPayResult.getOrDefault("zp_trans_id", 0)), 0, 1);
                result.put("dbStatus", "completed");
                result.put("autoConfirmed", true);
            }
        } catch (SQLException e) {
            Logger.error("Failed to query payment status", e);
            result.put("error", "Query failed");
        }
        return result;
    }

    public Map<String, Object> buyItNow(String token, String auctionId) {
        User buyer = requireUser(token);
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Auction auction = getAuctionEntity(conn, auctionId);
                if (!auction.hasBuyItNow()) throw new IllegalStateException("This auction does not have a buy-it-now price");
                if (auction.getState() != Auction.State.RUNNING) throw new IllegalStateException("Auction is not active");
                if (auction.getOwnerId().equals(buyer.getId())) throw new IllegalStateException("You cannot buy your own auction");
                double binPrice = auction.getBuyItNowPrice();
                String paymentId = UUID.randomUUID().toString();
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO payments (id, auction_id, buyer_id, buyer_username, amount, status, created_at) VALUES (?, ?, ?, ?, ?, 'pending', ?)")) {
                    ps.setString(1, paymentId); ps.setString(2, auctionId);
                    ps.setString(3, buyer.getId()); ps.setString(4, buyer.getUsername());
                    ps.setDouble(5, binPrice); ps.setLong(6, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET fulfillment_status = 'awaiting_payment', state = 'FINISHED', current_price = ?, highest_bidder_id = ?, highest_bidder_username = ? WHERE id = ?")) {
                    ps.setDouble(1, binPrice); ps.setString(2, buyer.getId()); ps.setString(3, buyer.getUsername()); ps.setString(4, auctionId);
                    ps.executeUpdate();
                }
                conn.commit();
                addNotification(auction.getOwnerId(), "PAYMENT", buyer.getUsername() + " bought your item for " + binPrice);
                long amountVnd = (long) (binPrice * 25000);
                String appTransId = paymentId.replace("-", "").substring(0, 32);
                String description = "Mua ngay #" + auctionId;
                String redirectUrl = baseUrl + "/payment-result";
                Map<String, Object> zaloPayResult = zaloPayService != null && zaloPayService.isConfigured()
                    ? zaloPayService.createOrder(appTransId, amountVnd, description, buyer.getUsername(), redirectUrl)
                    : zaloPayStubCreateOrder(appTransId, amountVnd);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("paymentId", paymentId);
                response.put("orderUrl", zaloPayResult.get("order_url"));
                response.put("orderToken", zaloPayResult.get("order_token"));
                response.put("amountVnd", amountVnd);
                response.put("message", "Buy-it-now initiated. Please complete payment via ZaloPay.");
                return response;
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Buy-it-now failed", e); }
    }

    public Map<String, Object> requestRefund(String token, String auctionId, String reason) {
        User user = requireUser(token);
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException("Reason is required (max 1000 chars)");
        String refundId = UUID.randomUUID().toString();
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String paymentId = null;
                double amount = 0;
                try (PreparedStatement ps = conn.prepareStatement("SELECT id, amount FROM payments WHERE auction_id = ? AND status = 'completed' ORDER BY created_at DESC LIMIT 1")) {
                    ps.setString(1, auctionId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        paymentId = rs.getString("id");
                        amount = rs.getDouble("amount");
                    }
                }
                if (paymentId == null) throw new IllegalStateException("No completed payment found for this auction");
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO refunds (id, payment_id, auction_id, requester_id, requester_username, amount, reason, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?)")) {
                    ps.setString(1, refundId); ps.setString(2, paymentId); ps.setString(3, auctionId);
                    ps.setString(4, user.getId()); ps.setString(5, user.getUsername());
                    ps.setDouble(6, amount); ps.setString(7, reason); ps.setLong(8, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                addNotification(user.getId(), "REFUND", "Refund request submitted for auction " + auctionId);
                try (PreparedStatement ps = conn.prepareStatement("SELECT owner_id FROM auctions WHERE id = ?")) {
                    ps.setString(1, auctionId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        addNotification(rs.getString("owner_id"), "REFUND", user.getUsername() + " requested a refund for auction " + auctionId);
                    }
                }
                conn.commit();
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Refund request failed", e); }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refundId", refundId);
        result.put("message", "Refund request submitted. Admin will review.");
        return result;
    }

    public List<Map<String, Object>> getRefunds(String token) {
        requireRole(token, User.Role.ADMIN);
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT r.id, r.auction_id, r.requester_username as username, r.amount, r.reason, r.status, r.created_at FROM refunds r ORDER BY r.created_at DESC")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("id"));
                row.put("auctionId", rs.getString("auction_id"));
                row.put("username", rs.getString("username"));
                row.put("amount", rs.getDouble("amount"));
                row.put("reason", rs.getString("reason"));
                row.put("status", rs.getString("status"));
                row.put("createdAt", rs.getLong("created_at"));
                result.add(row);
            }
        } catch (SQLException e) { throw new RuntimeException("Failed to get refunds", e); }
        return result;
    }

    public String processRefund(String token, String refundId, String action) {
        requireRole(token, User.Role.ADMIN);
        if (!Set.of("approved", "rejected").contains(action)) throw new IllegalArgumentException("Action must be 'approved' or 'rejected'");
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String paymentId, auctionId;
                double amount;
                try (PreparedStatement ps = conn.prepareStatement("SELECT payment_id, auction_id, amount FROM refunds WHERE id = ?")) {
                    ps.setString(1, refundId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new IllegalArgumentException("Refund not found");
                    paymentId = rs.getString("payment_id");
                    auctionId = rs.getString("auction_id");
                    amount = rs.getDouble("amount");
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE refunds SET status = ? WHERE id = ?")) {
                    ps.setString(1, action.equals("approved") ? "approved" : "rejected");
                    ps.setString(2, refundId);
                    ps.executeUpdate();
                }
                if ("approved".equals(action)) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE payments SET status = 'refunded' WHERE id = ?")) {
                        ps.setString(1, paymentId); ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET fulfillment_status = 'refunded' WHERE id = ?")) {
                        ps.setString(1, auctionId); ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Refund processing failed", e); }
        return "Refund " + action;
    }

    public AuctionView scheduleAuction(String token, String auctionId, long scheduledStartTime) {
        User seller = requireUser(token);
        if (scheduledStartTime <= System.currentTimeMillis()) throw new IllegalArgumentException("Scheduled time must be in the future");
        try (Connection conn = db.getConnection()) {
            Auction auction = getAuctionEntity(conn, auctionId);
            if (!auction.getOwnerId().equals(seller.getId()) && seller.getRole() != User.Role.ADMIN) throw new SecurityException("Unauthorized");
            if (auction.hasBids()) throw new IllegalStateException("Cannot reschedule an auction with bids");
            long duration = auction.getEndTime() - auction.getStartTime();
            long newEndTime = scheduledStartTime + duration;
            try (PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET start_time = ?, end_time = ?, state = 'OPEN' WHERE id = ?")) {
                ps.setLong(1, scheduledStartTime); ps.setLong(2, newEndTime); ps.setString(3, auctionId);
                ps.executeUpdate();
            }
            broadcast("AUCTION_RESCHEDULED:" + auctionId);
            return getAuction(auctionId);
        } catch (SQLException e) { throw new RuntimeException("Failed to schedule auction", e); }
    }

    public String markReceived(String token, String auctionId) {
        User user = requireUser(token);
        try (Connection conn = db.getConnection()) {
            Auction auction = getAuctionEntity(conn, auctionId);
            if (!user.getId().equals(auction.getHighestBidderId())) throw new SecurityException("Unauthorized");
            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM payments WHERE auction_id = ? ORDER BY created_at DESC")) {
                ps.setString(1, auctionId);
                ResultSet rs = ps.executeQuery();
                if (rs.next() && !"completed".equals(rs.getString("status"))) {
                    throw new IllegalStateException("Payment must be completed before marking as received");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET fulfillment_status = 'delivered' WHERE id = ?")) {
                ps.setString(1, auctionId); ps.executeUpdate();
            }
            return "Marked as received";
        } catch (SQLException e) { throw new RuntimeException("Failed to mark as received", e); }
    }

    public String reportDispute(String token, String auctionId, String reason) {
        User user = requireUser(token);
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException("Reason is required (max 1000 chars)");
        String disputeId = UUID.randomUUID().toString();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO disputes (id, auction_id, reporter_id, reporter_username, reason, status, created_at) VALUES (?, ?, ?, ?, ?, 'open', ?)")) {
            ps.setString(1, disputeId); ps.setString(2, auctionId);
            ps.setString(3, user.getId()); ps.setString(4, user.getUsername());
            ps.setString(5, reason); ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            addNotification("ADMIN", "DISPUTE", user.getUsername() + " reported auction " + auctionId);
            return "Dispute reported: " + disputeId;
        } catch (SQLException e) { throw new RuntimeException("Failed to report dispute", e); }
    }

    public List<Map<String, Object>> getDisputes(String token) {
        requireRole(token, User.Role.ADMIN);
        List<Map<String, Object>> disputes = new ArrayList<>();
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM disputes ORDER BY created_at DESC")) {
            while (rs.next()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", rs.getString("id"));
                d.put("auctionId", rs.getString("auction_id"));
                d.put("reporter", rs.getString("reporter_username"));
                d.put("reason", rs.getString("reason"));
                d.put("status", rs.getString("status"));
                d.put("resolution", rs.getString("resolution"));
                d.put("createdAt", rs.getLong("created_at"));
                disputes.add(d);
            }
        } catch (SQLException e) { throw new RuntimeException("Failed to load disputes", e); }
        return disputes;
    }

    public String resolveDispute(String token, String disputeId, String resolution, String action) {
        requireRole(token, User.Role.ADMIN);
        if (resolution == null || resolution.isBlank()) throw new IllegalArgumentException("Resolution is required");
        if (!Set.of("cancel", "refund", "none").contains(action)) throw new IllegalArgumentException("Invalid action");
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String auctionId, reporterId;
                try (PreparedStatement ps = conn.prepareStatement("SELECT auction_id, reporter_id FROM disputes WHERE id = ?")) {
                    ps.setString(1, disputeId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new IllegalArgumentException("Dispute not found");
                    auctionId = rs.getString("auction_id");
                    reporterId = rs.getString("reporter_id");
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE disputes SET status = 'resolved', resolution = ? WHERE id = ?")) {
                    ps.setString(1, resolution); ps.setString(2, disputeId); ps.executeUpdate();
                }
                if ("cancel".equals(action)) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET state = 'CANCELED' WHERE id = ?")) {
                        ps.setString(1, auctionId); ps.executeUpdate();
                    }
                }
                addNotification(reporterId, "DISPUTE", "Your dispute for auction " + auctionId + " has been resolved: " + resolution);
                conn.commit();
                return "Dispute resolved";
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException("Failed to resolve dispute", e); }
    }

    public List<Map<String, Object>> getNotifications(String token) {
        User user = requireUser(token);
        List<Map<String, Object>> notifications = new ArrayList<>();
        try (Connection conn = db.getConnection();
              PreparedStatement ps = conn.prepareStatement("SELECT * FROM notifications WHERE user_id = ?" + (user.getRole() == User.Role.ADMIN ? " OR user_id = 'ADMIN'" : "") + " ORDER BY created_at DESC")) {
            ps.setString(1, user.getId());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", rs.getString("id"));
                n.put("type", rs.getString("type"));
                n.put("message", rs.getString("message"));
                n.put("is_read", rs.getBoolean("is_read"));
                n.put("createdAt", rs.getLong("created_at"));
                notifications.add(n);
            }
        } catch (SQLException e) { throw new RuntimeException("Failed to load notifications", e); }
        return notifications;
    }

    public String markNotificationRead(String token, String notificationId) {
        User user = requireUser(token);
        try (Connection conn = db.getConnection();
              PreparedStatement ps = conn.prepareStatement("UPDATE notifications SET is_read = TRUE WHERE id = ? AND user_id = ?")) {
            ps.setString(1, notificationId); ps.setString(2, user.getId());
            ps.executeUpdate();
            return "Notification marked as read";
        } catch (SQLException e) { throw new RuntimeException("Failed to mark notification read", e); }
    }

    private void addNotification(String userId, String type, String message) {
        String id = UUID.randomUUID().toString();
        try (Connection conn = db.getConnection();
              PreparedStatement ps = conn.prepareStatement("INSERT INTO notifications (id, user_id, type, message, is_read, created_at) VALUES (?, ?, ?, ?, FALSE, ?)")) {
            ps.setString(1, id); ps.setString(2, userId);
            ps.setString(3, type); ps.setString(4, message);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            wsServer.broadcastMessage("NOTIFICATION:" + id);
        } catch (SQLException e) { System.err.println("Failed to add notification: " + e.getMessage()); }
    }

    private void generateInvoice(Connection conn, String paymentId, String auctionId, String buyerId) {
        try {
            String invoiceId = UUID.randomUUID().toString();
            String buyerUsername = "", sellerUsername = "", itemName = "";
            double amount = 0;
            String currency = "USD";
            try (PreparedStatement ps = conn.prepareStatement("SELECT p.amount, p.buyer_username, a.owner_username, a.item_name, a.currency FROM payments p JOIN auctions a ON p.auction_id = a.id WHERE p.id = ?")) {
                ps.setString(1, paymentId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    amount = rs.getDouble("amount");
                    buyerUsername = rs.getString("buyer_username");
                    sellerUsername = rs.getString("owner_username");
                    itemName = rs.getString("item_name");
                    currency = rs.getString("currency");
                    if (currency == null) currency = "USD";
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO invoices (id, payment_id, auction_id, buyer_id, buyer_username, seller_username, item_name, amount, currency, created_at, pdf_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, invoiceId); ps.setString(2, paymentId); ps.setString(3, auctionId);
                ps.setString(4, buyerId); ps.setString(5, buyerUsername); ps.setString(6, sellerUsername);
                ps.setString(7, itemName); ps.setDouble(8, amount); ps.setString(9, currency);
                ps.setLong(10, System.currentTimeMillis());
                ps.setString(11, "/api/invoices/" + invoiceId);
                ps.executeUpdate();
            }
        } catch (SQLException e) { System.err.println("Failed to generate invoice: " + e.getMessage()); }
    }

    public List<Map<String, Object>> getInvoices(String token) {
        User user = requireUser(token);
        List<Map<String, Object>> invoices = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM invoices WHERE buyer_id = ? ORDER BY created_at DESC")) {
            ps.setString(1, user.getId());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> inv = new LinkedHashMap<>();
                inv.put("id", rs.getString("id"));
                inv.put("paymentId", rs.getString("payment_id"));
                inv.put("auctionId", rs.getString("auction_id"));
                inv.put("sellerUsername", rs.getString("seller_username"));
                inv.put("itemName", rs.getString("item_name"));
                inv.put("amount", rs.getDouble("amount"));
                inv.put("currency", rs.getString("currency"));
                inv.put("createdAt", rs.getLong("created_at"));
                inv.put("pdfUrl", rs.getString("pdf_url"));
                invoices.add(inv);
            }
        } catch (SQLException e) { throw new RuntimeException("Failed to load invoices", e); }
        return invoices;
    }

    // --- WATCHLIST ---

    public List<AuctionView> getWatchlist(String token) {
        User user = requireUser(token);
        List<AuctionView> views = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT a.* FROM auctions a JOIN watchlist w ON a.id = w.auction_id WHERE w.user_id = ? ORDER BY w.added_at DESC")) {
            ps.setString(1, user.getId());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Auction auction = mapResultSetToAuction(rs, conn);
                views.add(new AuctionView(auction));
            }
        } catch (SQLException e) { throw new RuntimeException("Failed to load watchlist", e); }
        return views;
    }

    public String addToWatchlist(String token, String auctionId) {
        User user = requireUser(token);
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("MERGE INTO watchlist (user_id, auction_id, added_at) KEY(user_id, auction_id) VALUES (?,?,?)")) {
            ps.setString(1, user.getId()); ps.setString(2, auctionId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            return "Added to watchlist";
        } catch (SQLException e) { throw new RuntimeException("Failed to add to watchlist", e); }
    }

    public String removeFromWatchlist(String token, String auctionId) {
        User user = requireUser(token);
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM watchlist WHERE user_id = ? AND auction_id = ?")) {
            ps.setString(1, user.getId()); ps.setString(2, auctionId);
            ps.executeUpdate();
            return "Removed from watchlist";
        } catch (SQLException e) { throw new RuntimeException("Failed to remove from watchlist", e); }
    }

    // --- ACCOUNT HISTORY ---

    public Map<String, Object> getAccountHistory(String token) {
        User user = requireUser(token);
        Map<String, Object> history = new LinkedHashMap<>();
        try (Connection conn = db.getConnection()) {
            List<Map<String, Object>> bids = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT b.*, a.item_name, a.state FROM bids b JOIN auctions a ON b.auction_id = a.id WHERE b.bidder_id = ? ORDER BY b.timestamp DESC LIMIT 50")) {
                ps.setString(1, user.getId());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("auctionId", rs.getString("auction_id"));
                    b.put("itemName", rs.getString("item_name"));
                    b.put("amount", rs.getDouble("amount"));
                    b.put("state", rs.getString("state"));
                    b.put("timestamp", rs.getLong("timestamp"));
                    bids.add(b);
                }
            }
            List<Map<String, Object>> auctions = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, item_name, state, current_price, end_time FROM auctions WHERE owner_id = ? ORDER BY end_time DESC LIMIT 50")) {
                ps.setString(1, user.getId());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("id", rs.getString("id"));
                    a.put("itemName", rs.getString("item_name"));
                    a.put("state", rs.getString("state"));
                    a.put("currentPrice", rs.getDouble("current_price"));
                    a.put("endTime", rs.getLong("end_time"));
                    auctions.add(a);
                }
            }
            List<Map<String, Object>> won = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT a.id, a.item_name, a.current_price, a.fulfillment_status FROM auctions a WHERE a.highest_bidder_id = ? AND a.state = 'FINISHED' ORDER BY a.end_time DESC")) {
                ps.setString(1, user.getId());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("id", rs.getString("id"));
                    w.put("itemName", rs.getString("item_name"));
                    w.put("price", rs.getDouble("current_price"));
                    w.put("fulfillmentStatus", rs.getString("fulfillment_status"));
                    won.add(w);
                }
            }
            history.put("bids", bids);
            history.put("myAuctions", auctions);
            history.put("wonAuctions", won);
        } catch (SQLException e) { throw new RuntimeException("Failed to load account history", e); }
        return history;
    }

    // --- 2FA/TOTP ---

    public Map<String, Object> setup2FA(String token) {
        User user = requireUser(token);
        java.security.SecureRandom random = new java.security.SecureRandom();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        String base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) { sb.append(base32Chars.charAt((b & 0xFF) % 32)); }
        String totpSecret = sb.toString();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET totp_secret = ? WHERE id = ?")) {
            ps.setString(1, totpSecret); ps.setString(2, user.getId());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to setup 2FA", e); }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("secret", totpSecret);
        result.put("qrUrl", "otpauth://totp/AuctionSystem:" + user.getUsername() + "?secret=" + totpSecret + "&issuer=AuctionSystem");
        return result;
    }

    public String enable2FA(String token, String code) {
        User user = requireUser(token);
        if (user.getTotpSecret() == null) throw new IllegalStateException("2FA not setup");
        if (!verifyTotpCode(user.getTotpSecret(), code)) throw new IllegalArgumentException("Invalid TOTP code");
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET totp_enabled = TRUE WHERE id = ?")) {
            ps.setString(1, user.getId()); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to enable 2FA", e); }
        return "2FA enabled";
    }

    public String disable2FA(String token, String code) {
        User user = requireUser(token);
        if (!user.isTotpEnabled()) throw new IllegalStateException("2FA not enabled");
        if (!verifyTotpCode(user.getTotpSecret(), code)) throw new IllegalArgumentException("Invalid TOTP code");
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET totp_enabled = FALSE, totp_secret = NULL WHERE id = ?")) {
            ps.setString(1, user.getId()); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to disable 2FA", e); }
        return "2FA disabled";
    }

    private boolean verifyTotpCode(String secret, String code) {
        // Simplified TOTP verification - in production use a proper library like googleauth
        if (code == null || code.length() != 6) return false;
        try {
            long timeStep = 30;
            long currentTime = System.currentTimeMillis() / 1000;
            long timeCounter = currentTime / timeStep;
            for (int i = -1; i <= 1; i++) {
                if (generateTotp(secret, timeCounter + i).equals(code)) return true;
            }
        } catch (Exception e) { return false; }
        return false;
    }

    private String generateTotp(String secret, long timeCounter) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] timeBytes = java.nio.ByteBuffer.allocate(8).putLong(timeCounter).array();
            byte[] hash = mac.doFinal(timeBytes);
            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16) | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%06d", binary % 1000000);
        } catch (Exception e) { return ""; }
    }

    // --- CATEGORIES ---

    public List<String> getCategories() {
        List<String> categories = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT item_type FROM auctions WHERE item_type IS NOT NULL ORDER BY item_type")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) categories.add(rs.getString("item_type"));
        } catch (SQLException e) { throw new RuntimeException("Failed to load categories", e); }
        return categories;
    }

    // --- PAGINATION ---

    public Map<String, Object> getAuctionsPaginated(int page, int limit, String category, String search) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = db.getConnection()) {
            String countSql = "SELECT COUNT(*) FROM auctions WHERE 1=1";
            String dataSql = "SELECT * FROM auctions WHERE 1=1";
            List<String> conditions = new ArrayList<>();
            if (category != null && !category.isBlank()) { conditions.add("item_type = ?"); }
            if (search != null && !search.isBlank()) { conditions.add("(item_name LIKE ? OR description LIKE ?)"); }
            if (!conditions.isEmpty()) {
                String where = " AND " + String.join(" AND ", conditions);
                countSql += where;
                dataSql += where;
            }
            dataSql += " ORDER BY end_time ASC LIMIT ? OFFSET ?";
            int total;
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                int idx = 1;
                if (category != null && !category.isBlank()) { ps.setString(idx++, category); }
                if (search != null && !search.isBlank()) { ps.setString(idx++, "%" + search + "%"); ps.setString(idx++, "%" + search + "%"); }
                ResultSet rs = ps.executeQuery();
                total = rs.next() ? rs.getInt(1) : 0;
            }
            List<AuctionView> views = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(dataSql)) {
                int idx = 1;
                if (category != null && !category.isBlank()) { ps.setString(idx++, category); }
                if (search != null && !search.isBlank()) { ps.setString(idx++, "%" + search + "%"); ps.setString(idx++, "%" + search + "%"); }
                ps.setInt(idx++, limit);
                ps.setInt(idx++, (page - 1) * limit);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Auction auction = mapResultSetToAuction(rs, conn);
                    views.add(new AuctionView(auction));
                }
            }
            result.put("auctions", views);
            result.put("total", total);
            result.put("page", page);
            result.put("limit", limit);
            result.put("totalPages", (int) Math.ceil((double) total / limit));
        } catch (SQLException e) { throw new RuntimeException("Failed to load auctions", e); }
        return result;
    }

    // --- BACKUP ---

    public String exportBackup(String token) {
        requireRole(token, User.Role.ADMIN);
        try {
            java.nio.file.Path backupDir = java.nio.file.Path.of("data", "backups");
            java.nio.file.Files.createDirectories(backupDir);
            String filename = "backup-" + System.currentTimeMillis() + ".json";
            java.nio.file.Path backupPath = backupDir.resolve(filename);
            Map<String, Object> backup = new LinkedHashMap<>();
            try (Connection conn = db.getConnection()) {
                List<Map<String, Object>> users = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM users"); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> u = new LinkedHashMap<>();
                        u.put("id", rs.getString("id")); u.put("username", rs.getString("username"));
                        u.put("role", rs.getString("role")); u.put("auction_limit", rs.getInt("auction_limit"));
                        u.put("created_at", rs.getLong("created_at")); u.put("email", rs.getString("email"));
                        u.put("email_verified", rs.getBoolean("email_verified"));
                        users.add(u);
                    }
                }
                backup.put("users", users);
                List<Map<String, Object>> auctions = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM auctions"); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> a = new LinkedHashMap<>();
                        a.put("id", rs.getString("id")); a.put("item_id", rs.getString("item_id"));
                        a.put("item_type", rs.getString("item_type")); a.put("item_name", rs.getString("item_name"));
                        a.put("description", rs.getString("description")); a.put("starting_price", rs.getDouble("starting_price"));
                        a.put("owner_id", rs.getString("owner_id")); a.put("owner_username", rs.getString("owner_username"));
                        a.put("start_time", rs.getLong("start_time")); a.put("end_time", rs.getLong("end_time"));
                        a.put("current_price", rs.getDouble("current_price")); a.put("state", rs.getString("state"));
                        a.put("fulfillment_status", rs.getString("fulfillment_status"));
                        auctions.add(a);
                    }
                }
                backup.put("auctions", auctions);
                List<Map<String, Object>> bids = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM bids"); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("auction_id", rs.getString("auction_id")); b.put("bidder_id", rs.getString("bidder_id"));
                        b.put("bidder_username", rs.getString("bidder_username")); b.put("amount", rs.getDouble("amount"));
                        b.put("timestamp", rs.getLong("timestamp"));
                        bids.add(b);
                    }
                }
                backup.put("bids", bids);
                List<Map<String, Object>> payments = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM payments"); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("id", rs.getString("id")); p.put("auction_id", rs.getString("auction_id"));
                        p.put("buyer_id", rs.getString("buyer_id")); p.put("amount", rs.getDouble("amount"));
                        p.put("status", rs.getString("status")); p.put("created_at", rs.getLong("created_at"));
                        payments.add(p);
                    }
                }
                backup.put("payments", payments);
            }
            try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(backupPath)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(backup, writer);
            }
            return "Backup created: " + filename;
        } catch (Exception e) { throw new RuntimeException("Backup failed", e); }
    }

    // --- SYSTEM METRICS ---

    public Map<String, Object> getSystemMetrics(String token) {
        requireRole(token, User.Role.ADMIN);
        Map<String, Object> metrics = new LinkedHashMap<>();
        try (Connection conn = db.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM users"); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) metrics.put("totalUsers", rs.getInt(1));
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM auctions WHERE state = 'RUNNING'"); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) metrics.put("activeAuctions", rs.getInt(1));
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM auctions WHERE state = 'FINISHED'"); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) metrics.put("finishedAuctions", rs.getInt(1));
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM bids"); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) metrics.put("totalBids", rs.getInt(1));
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM payments WHERE status = 'completed'"); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) metrics.put("completedPayments", rs.getInt(1));
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM disputes WHERE status = 'open'"); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) metrics.put("openDisputes", rs.getInt(1));
            }
        } catch (SQLException e) { throw new RuntimeException("Failed to load metrics", e); }
        metrics.put("dbType", db.isPostgres() ? "PostgreSQL" : "H2");
        metrics.put("timestamp", System.currentTimeMillis());
        metrics.put("connectionPool", db.getPoolStats());
        return metrics;
    }

    // --- HELPERS ---

    private User findUserByUsername(String username) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE username = ?")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapResultSetToUser(rs);
        } catch (SQLException e) { /* silent */ }
        return null;
    }

    private void updateUser(User user) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET password = ?, role = ?, auction_limit = ?, email = ?, email_verified = ?, failed_login_attempts = ?, locked_until = ?, totp_secret = ?, totp_enabled = ? WHERE id = ?")) {
            ps.setString(1, user.getPasswordHashForStorage()); ps.setString(2, user.getRole().name());
            ps.setInt(3, user.getAuctionLimit()); ps.setString(4, user.getEmail());
            ps.setBoolean(5, user.isEmailVerified()); ps.setInt(6, user.getFailedLoginAttempts());
            ps.setLong(7, user.getLockedUntil()); ps.setString(8, user.getTotpSecret());
            ps.setBoolean(9, user.isTotpEnabled()); ps.setString(10, user.getId());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Failed to update user: " + user.getUsername(), e); }
    }

    private long getActiveAuctionCount(String ownerId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM auctions WHERE owner_id = ? AND state = 'RUNNING'")) {
            ps.setString(1, ownerId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) { throw new RuntimeException("Database error counting auctions", e); }
        return 0;
    }

    private User mapResultSetToUser(ResultSet rs) throws SQLException {
        String email = rs.getString("email");
        boolean emailVerified = false;
        try { emailVerified = rs.getBoolean("email_verified"); } catch (SQLException ignored) {}
        int failedAttempts = 0;
        try { failedAttempts = rs.getInt("failed_login_attempts"); } catch (SQLException ignored) {}
        long lockedUntil = 0;
        try { lockedUntil = rs.getLong("locked_until"); } catch (SQLException ignored) {}
        String totpSecret = null;
        try { totpSecret = rs.getString("totp_secret"); } catch (SQLException ignored) {}
        boolean totpEnabled = false;
        try { totpEnabled = rs.getBoolean("totp_enabled"); } catch (SQLException ignored) {}
        return User.fromStoredHash(rs.getString("id"), rs.getString("username"), rs.getString("password"),
            User.Role.valueOf(rs.getString("role")), rs.getInt("auction_limit"), rs.getLong("created_at"),
            email, emailVerified, failedAttempts, lockedUntil, totpSecret, totpEnabled);
    }

    private Auction mapResultSetToAuctionWithoutBids(ResultSet rs) throws SQLException {
        Item item = ItemFactory.createItem(rs.getString("item_type"), rs.getString("item_id"), rs.getString("item_name"), rs.getString("description"), rs.getDouble("starting_price"), rs.getString("extra_info"));
        Auction auction = new Auction(rs.getString("id"), item, rs.getString("owner_id"), rs.getString("owner_username"), rs.getLong("start_time"), rs.getLong("end_time"));
        auction.setState(Auction.State.valueOf(rs.getString("state")));
        auction.setImageFilename(rs.getString("image_filename"));
        auction.setCurrentPrice(rs.getDouble("current_price"));
        auction.setHighestBidder(rs.getString("highest_bidder_id"), rs.getString("highest_bidder_username"));
        try { auction.setCurrency(rs.getString("currency")); } catch (SQLException ignored) {}
        try { auction.setBuyItNowPrice(rs.getDouble("buy_it_now_price")); } catch (SQLException ignored) {}
        try { auction.setReservePrice(rs.getDouble("reserve_price")); } catch (SQLException ignored) {}
        return auction;
    }

    private Auction mapResultSetToAuction(ResultSet rs, Connection conn) throws SQLException {
        Auction auction = mapResultSetToAuctionWithoutBids(rs);
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM bids WHERE auction_id = ? ORDER BY timestamp ASC")) {
            ps.setString(1, auction.getId());
            ResultSet brs = ps.executeQuery();
            while (brs.next()) auction.getBidHistory().add(new BidTransaction(brs.getString("bidder_id"), brs.getString("bidder_username"), brs.getDouble("amount"), brs.getLong("timestamp")));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load bids for auction " + auction.getId(), e);
        }
        return auction;
    }

    private Auction getAuctionEntity(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM auctions WHERE id = ?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapResultSetToAuction(rs, conn);
        }
        throw new IllegalArgumentException("Auction not found");
    }

    private void updateAuctionState(Connection conn, Auction a) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET current_price = ?, highest_bidder_id = ?, highest_bidder_username = ?, end_time = ?, state = ? WHERE id = ?")) {
            ps.setDouble(1, a.getCurrentPrice()); ps.setString(2, a.getHighestBidderId());
            ps.setString(3, a.getHighestBidderUsername()); ps.setLong(4, a.getEndTime());
            ps.setString(5, a.getState().name()); ps.setString(6, a.getId());
            ps.executeUpdate();
        }
    }

    private void saveBid(Connection conn, String auctionId, User bidder, double amount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO bids (id, auction_id, bidder_id, bidder_username, amount, timestamp) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, auctionId); ps.setString(3, bidder.getId());
            ps.setString(4, bidder.getUsername()); ps.setDouble(5, amount);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private void seedDataIfEmpty() {
        if (findUserByUsername("admin") != null) return;
        try {
            String adminId = UUID.randomUUID().toString();
            User admin = new User(adminId, "admin", generateRandomPassword(), User.Role.ADMIN);
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO users (id, username, password, role, auction_limit, created_at, email, email_verified, failed_login_attempts, locked_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, admin.getId()); ps.setString(2, admin.getUsername());
                ps.setString(3, admin.getPasswordHashForStorage()); ps.setString(4, User.Role.ADMIN.name());
                ps.setInt(5, admin.getAuctionLimit()); ps.setLong(6, admin.getCreatedAt());
                ps.setString(7, null); ps.setBoolean(8, false); ps.setInt(9, 0); ps.setLong(10, 0);
                ps.executeUpdate();
            }
            register("seller1", "TestP@ss1!", "USER");
            register("bidder1", "TestP@ss2!", "USER");
        } catch (Exception e) {
            Logger.warn("Seed data failed: " + e.getMessage());
        }
    }
    private String generateRandomPassword() {
        String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower = upper.toLowerCase();
        String digits = "0123456789";
        String special = "!@#$%^&*";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        sb.append(upper.charAt(random.nextInt(upper.length())));
        sb.append(lower.charAt(random.nextInt(lower.length())));
        sb.append(digits.charAt(random.nextInt(digits.length())));
        sb.append(special.charAt(random.nextInt(special.length())));
        for (int i = 0; i < 12; i++) {
            String all = upper + lower + digits + special;
            sb.append(all.charAt(random.nextInt(all.length())));
        }
        return sb.toString();
    }


    private volatile boolean monitorRunning = true;

    private void startAuctionMonitor() {
        new Thread(() -> {
            while (monitorRunning) {
                try (Connection conn = db.getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM auctions WHERE state = 'RUNNING' OR state = 'OPEN'")) {
                    long now = System.currentTimeMillis();
                    while (rs.next()) {
                        String id = rs.getString("id");
                        long endTime = rs.getLong("end_time");
                        String currentState = rs.getString("state");
                        if (now > endTime && ("RUNNING".equals(currentState) || "OPEN".equals(currentState))) {
                            try (PreparedStatement ups = conn.prepareStatement("UPDATE auctions SET state = 'FINISHED' WHERE id = ?")) { ups.setString(1, id); ups.executeUpdate(); broadcast("AUCTION_STATE_CHANGED:ALL"); }
                        }
                    }
                } catch (Exception e) {
                    Logger.warn("Auction monitor error: " + e.getMessage());
                }
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }, "AuctionMonitor").start();
    }

    public void shutdown() {
        monitorRunning = false;
        wsServer.stop();
        db.shutdown();
    }

    public void logout(String token) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE token = ?")) {
            ps.setString(1, token); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("Logout failed", e); }
    }

    private SessionView toSessionView(String token, User user) {
        return new SessionView(token, user.getId(), user.getUsername(), user.getRole(), user.getAuctionLimit(), user.getCreatedAt());
    }

    private UserView toUserView(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getRole(), user.getAuctionLimit(), user.getCreatedAt());
    }

    public DatabaseManager getDbManager() {
        return db;
    }
}
