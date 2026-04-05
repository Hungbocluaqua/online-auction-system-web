package com.auction.web.service;

import com.auction.web.dto.AuctionCreateRequest;
import com.auction.web.dto.AuctionUpdateRequest;
import com.auction.web.dto.AuctionView;
import com.auction.web.dto.SessionView;
import com.auction.web.dto.UserView;
import com.auction.web.model.*;
import com.auction.web.persistence.SnapshotStore;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class AuctionService {
    private final DatabaseManager db = new DatabaseManager();
    private final WebSocketServerImpl wsServer = new WebSocketServerImpl(8889);
    private final Map<String, String> sessionToUsername = new ConcurrentHashMap<>();
    private final List<String> eventQueue = new CopyOnWriteArrayList<>();

    public AuctionService() {
        this(null);
    }

    public AuctionService(SnapshotStore snapshotStore) {
        wsServer.start();
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
            .filter(e -> Long.parseLong(e.split(":")[e.split(":").length - 1]) > since)
            .toList();
    }

    public SessionView login(String username, String password) {
        User user = findUserByUsername(username);
        if (user != null && user.checkPassword(password)) {
            String token = UUID.randomUUID().toString();
            sessionToUsername.put(token, username);
            return toSessionView(token, user);
        }
        throw new IllegalArgumentException("Invalid credentials");
    }

    public SessionView register(String username, String password, String role) {
        String id = UUID.randomUUID().toString();
        User.Role userRole = User.Role.valueOf(role.toUpperCase());
        User user = new User(id, username, password, userRole);
        
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO users (id, username, password, role, auction_limit, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, user.getId());
            ps.setString(2, user.getUsername());
            ps.setString(3, user.getPasswordHashForStorage());
            ps.setString(4, user.getRole().name());
            ps.setInt(5, user.getAuctionLimit());
            ps.setLong(6, user.getCreatedAt());
            ps.executeUpdate();
            return login(username, password);
        } catch (SQLException e) {
            throw new IllegalArgumentException("Username already exists or database error");
        }
    }

    public SessionView requireSession(String token) {
        User user = requireUser(token);
        return toSessionView(token, user);
    }

    public User requireUser(String token) {
        String username = sessionToUsername.get(token);
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

    public SessionView changePassword(String token, String currentPassword, String newPassword) {
        User user = requireUser(token);
        if (!user.checkPassword(currentPassword)) throw new IllegalArgumentException("Incorrect current password");
        user.setPassword(newPassword);
        updateUser(user);
        return toSessionView(token, user);
    }

    public SessionView updateUsername(String token, String newUsername) {
        User user = requireUser(token);
        String oldUsername = user.getUsername();
        user.setUsername(newUsername);
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET username = ? WHERE id = ?")) {
            ps.setString(1, newUsername);
            ps.setString(2, user.getId());
            ps.executeUpdate();
            sessionToUsername.remove(token);
            sessionToUsername.put(token, newUsername);
            return toSessionView(token, user);
        } catch (SQLException e) {
            user.setUsername(oldUsername);
            throw new IllegalArgumentException("Username already taken");
        }
    }

    public String deleteAccount(String token, String password) {
        User user = requireUser(token);
        if (!user.checkPassword(password)) throw new IllegalArgumentException("Incorrect password");
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setString(1, user.getId());
            ps.executeUpdate();
            sessionToUsername.remove(token);
            return "Account deleted";
        } catch (SQLException e) {
            throw new RuntimeException("Delete failed");
        }
    }

    public List<UserView> updateAuctionLimit(String token, String targetUsername, int newLimit) {
        requireRole(token, User.Role.ADMIN);
        User target = findUserByUsername(targetUsername);
        if (target == null) throw new IllegalArgumentException("User not found");
        target.setAuctionLimit(newLimit);
        updateUser(target);
        return getUsers(token);
    }

    public List<AuctionView> getAuctions() {
        List<AuctionView> views = new ArrayList<>();
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM auctions ORDER BY end_time ASC")) {
            while (rs.next()) {
                views.add(new AuctionView(mapResultSetToAuction(rs)));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return views;
    }

    public AuctionView getAuction(String id) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM auctions WHERE id = ?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return new AuctionView(mapResultSetToAuction(rs));
        } catch (SQLException e) { e.printStackTrace(); }
        throw new IllegalArgumentException("Auction not found");
    }

    public List<UserView> getUsers(String token) {
        requireRole(token, User.Role.ADMIN);
        List<UserView> users = new ArrayList<>();
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users ORDER BY username")) {
            while (rs.next()) {
                users.add(toUserView(mapResultSetToUser(rs)));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return users;
    }

    public AuctionView createAuction(String token, AuctionCreateRequest request) {
        User seller = requireUser(token);
        // Check limit
        long count = getActiveAuctionCount(seller.getId());
        if (seller.getRole() != User.Role.ADMIN && count >= seller.getAuctionLimit()) {
            throw new IllegalStateException("Auction limit reached (" + seller.getAuctionLimit() + ")");
        }

        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Item item = ItemFactory.createItem(request.itemType, UUID.randomUUID().toString(), request.name, request.description, request.startingPrice, request.extraInfo);
        
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO auctions (id, item_id, item_type, item_name, description, starting_price, extra_info, owner_id, owner_username, start_time, end_time, current_price, image_filename, state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, item.getId()); ps.setString(3, item.getType());
            ps.setString(4, item.getName()); ps.setString(5, item.getDescription()); ps.setDouble(6, item.getStartingPrice());
            ps.setString(7, item.getExtraValue()); ps.setString(8, seller.getId()); ps.setString(9, seller.getUsername());
            ps.setLong(10, now); ps.setLong(11, now + (request.durationMinutes * 60_000));
            ps.setDouble(12, item.getStartingPrice()); ps.setString(13, request.imageFilename == null ? "" : request.imageFilename);
            ps.setString(14, "RUNNING");
            ps.executeUpdate();
            broadcast("AUCTION_ADDED:" + id);
            return getAuction(id);
        } catch (SQLException e) { e.printStackTrace(); }
        throw new RuntimeException("Failed to create auction");
    }

    public AuctionView updateAuction(String token, String auctionId, AuctionUpdateRequest request) {
        User seller = requireUser(token);
        try (Connection conn = db.getConnection()) {
            Auction auction = getAuctionEntity(conn, auctionId);
            if (!auction.getOwnerId().equals(seller.getId()) && seller.getRole() != User.Role.ADMIN) throw new SecurityException("Unauthorized");
            if (auction.hasBids()) throw new IllegalStateException("Cannot edit with bids");

            Item item = ItemFactory.createItem(request.itemType, auction.getItem().getId(), request.name, request.description, request.startingPrice, request.extraInfo);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET item_type=?, item_name=?, description=?, starting_price=?, extra_info=?, current_price=? WHERE id=?")) {
                ps.setString(1, item.getType()); ps.setString(2, item.getName());
                ps.setString(3, item.getDescription()); ps.setDouble(4, item.getStartingPrice());
                ps.setString(5, item.getExtraValue()); ps.setDouble(6, item.getStartingPrice());
                ps.setString(7, auctionId);
                ps.executeUpdate();
            }
            broadcast("AUCTION_UPDATED:" + auctionId);
            return getAuction(auctionId);
        } catch (SQLException e) { e.printStackTrace(); }
        throw new RuntimeException("Update failed");
    }

    public void deleteAuction(String token, String auctionId) {
        User seller = requireUser(token);
        try (Connection conn = db.getConnection()) {
            Auction auction = getAuctionEntity(conn, auctionId);
            if (!auction.getOwnerId().equals(seller.getId()) && seller.getRole() != User.Role.ADMIN) throw new SecurityException("Unauthorized");
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM auctions WHERE id = ?")) {
                ps.setString(1, auctionId);
                ps.executeUpdate();
            }
            broadcast("AUCTION_DELETED:" + auctionId);
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public AuctionView cancelAuction(String token, String auctionId) {
        requireRole(token, User.Role.ADMIN);
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE auctions SET state = 'CANCELED' WHERE id = ?")) {
            ps.setString(1, auctionId);
            ps.executeUpdate();
            broadcast("AUCTION_STATE_CHANGED:" + auctionId);
            return getAuction(auctionId);
        } catch (SQLException e) { e.printStackTrace(); }
        throw new RuntimeException("Cancel failed");
    }

    public AuctionView placeBid(String token, String auctionId, double amount) {
        User bidder = requireUser(token);
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            Auction auction = getAuctionEntity(conn, auctionId);
            if (auction.placeBid(bidder, amount)) {
                updateAuctionState(conn, auction);
                saveBid(conn, auctionId, bidder, amount);
                conn.commit();
                broadcast("BID_PLACED:" + auctionId);
                evaluateAutoBids(auctionId);
                return getAuction(auctionId);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        throw new IllegalStateException("Bid failed");
    }

    public AuctionView configureAutoBid(String token, String auctionId, double maxBid, double increment) {
        User bidder = requireUser(token);
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("MERGE INTO autobids (auction_id, bidder_id, bidder_username, max_bid, increment) KEY(auction_id, bidder_id) VALUES (?,?,?,?,?)")) {
            ps.setString(1, auctionId); ps.setString(2, bidder.getId());
            ps.setString(3, bidder.getUsername()); ps.setDouble(4, maxBid);
            ps.setDouble(5, increment);
            ps.executeUpdate();
            broadcast("AUTO_BID_CONFIGURED:" + auctionId);
            evaluateAutoBids(auctionId);
            return getAuction(auctionId);
        } catch (SQLException e) { e.printStackTrace(); }
        throw new RuntimeException("Auto-bid setup failed");
    }

    private void evaluateAutoBids(String auctionId) {
        try (Connection conn = db.getConnection()) {
            boolean changed = true;
            while (changed) {
                changed = false;
                Auction auction = getAuctionEntity(conn, auctionId);
                if (auction.getState() != Auction.State.RUNNING) break;

                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM autobids WHERE auction_id = ? AND bidder_id <> ? ORDER BY max_bid DESC")) {
                    ps.setString(1, auctionId);
                    ps.setString(2, auction.getHighestBidderId() == null ? "" : auction.getHighestBidderId());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        double maxBid = rs.getDouble("max_bid");
                        double increment = rs.getDouble("increment");
                        double nextBid = auction.getCurrentPrice() + increment;
                        if (nextBid <= maxBid) {
                            User autoBidder = new User(rs.getString("bidder_id"), rs.getString("bidder_username"), "", User.Role.USER);
                            if (auction.placeBid(autoBidder, nextBid)) {
                                updateAuctionState(conn, auction);
                                saveBid(conn, auctionId, autoBidder, nextBid);
                                broadcast("BID_PLACED:" + auctionId);
                                changed = true;
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private User findUserByUsername(String username) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE username = ?")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapResultSetToUser(rs);
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    private void updateUser(User user) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET password = ?, role = ?, auction_limit = ? WHERE id = ?")) {
            ps.setString(1, user.getPasswordHashForStorage());
            ps.setString(2, user.getRole().name());
            ps.setInt(3, user.getAuctionLimit());
            ps.setString(4, user.getId());
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private long getActiveAuctionCount(String ownerId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM auctions WHERE owner_id = ? AND state = 'RUNNING'")) {
            ps.setString(1, ownerId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    private User mapResultSetToUser(ResultSet rs) throws SQLException {
        return User.fromStoredHash(rs.getString("id"), rs.getString("username"), rs.getString("password"), User.Role.valueOf(rs.getString("role")), rs.getInt("auction_limit"), rs.getLong("created_at"));
    }

    private Auction mapResultSetToAuction(ResultSet rs) throws SQLException {
        Item item = ItemFactory.createItem(rs.getString("item_type"), rs.getString("item_id"), rs.getString("item_name"), rs.getString("description"), rs.getDouble("starting_price"), rs.getString("extra_info"));
        Auction auction = new Auction(rs.getString("id"), item, rs.getString("owner_id"), rs.getString("owner_username"), rs.getLong("start_time"), rs.getLong("end_time"));
        auction.setState(Auction.State.valueOf(rs.getString("state")));
        auction.setImageFilename(rs.getString("image_filename"));
        
        try (PreparedStatement ps = rs.getStatement().getConnection().prepareStatement("SELECT * FROM bids WHERE auction_id = ? ORDER BY timestamp ASC")) {
            ps.setString(1, auction.getId());
            ResultSet brs = ps.executeQuery();
            while (brs.next()) {
                auction.getBidHistory().add(new BidTransaction(brs.getString("bidder_id"), brs.getString("bidder_username"), brs.getDouble("amount"), brs.getLong("timestamp")));
            }
        }
        return auction;
    }

    private Auction getAuctionEntity(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM auctions WHERE id = ?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapResultSetToAuction(rs);
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
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO bids (auction_id, bidder_id, bidder_username, amount, timestamp) VALUES (?,?,?,?,?)")) {
            ps.setString(1, auctionId); ps.setString(2, bidder.getId());
            ps.setString(3, bidder.getUsername()); ps.setDouble(4, amount);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private void seedDataIfEmpty() {
        try (Connection conn = db.getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next() && rs.getInt(1) == 0) {
                register("admin", "password", "ADMIN");
                register("seller1", "password", "USER");
                register("bidder1", "password", "USER");
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void startAuctionMonitor() {
        new Thread(() -> {
            while (true) {
                try (Connection conn = db.getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM auctions WHERE state = 'RUNNING' OR state = 'OPEN'")) {
                    long now = System.currentTimeMillis();
                    while (rs.next()) {
                        String id = rs.getString("id");
                        long endTime = rs.getLong("end_time");
                        String currentState = rs.getString("state");
                        if (now > endTime && "RUNNING".equals(currentState)) {
                            try (PreparedStatement ups = conn.prepareStatement("UPDATE auctions SET state = 'FINISHED' WHERE id = ?")) {
                                ups.setString(1, id); ups.executeUpdate();
                                broadcast("AUCTION_STATE_CHANGED:ALL");
                            }
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }, "AuctionMonitor").start();
    }

    public void logout(String token) { sessionToUsername.remove(token); }

    private SessionView toSessionView(String token, User user) {
        return new SessionView(token, user.getId(), user.getUsername(), user.getRole(), user.getAuctionLimit(), user.getCreatedAt());
    }

    private UserView toUserView(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getRole(), user.getAuctionLimit(), user.getCreatedAt());
    }
}
