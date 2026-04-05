package com.auction.web.persistence;

import com.auction.web.model.AutoBidConfig;
import com.auction.web.model.Auction;
import com.auction.web.model.BidTransaction;
import com.auction.web.model.Item;
import com.auction.web.model.ItemFactory;
import com.auction.web.model.User;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DatabaseSnapshotStore implements SnapshotStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public DatabaseSnapshotStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public static DatabaseSnapshotStore forFile(Path dbPath) {
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create database directory", ex);
        }
        String normalized = dbPath.toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:h2:file:" + normalized + ";AUTO_SERVER=TRUE";
        return new DatabaseSnapshotStore(url, "sa", "");
    }

    @Override
    public Snapshot load() throws IOException {
        try (Connection connection = openConnection()) {
            migrate(connection);
            List<User> users = loadUsers(connection);
            List<Auction> auctions = loadAuctions(connection);
            if (users.isEmpty() && auctions.isEmpty()) {
                return null;
            }
            return new Snapshot(users, auctions);
        } catch (SQLException ex) {
            throw new IOException("Unable to load database snapshot", ex);
        }
    }

    @Override
    public void save(Collection<User> users, Collection<Auction> auctions) throws IOException {
        try (Connection connection = openConnection()) {
            migrate(connection);
            connection.setAutoCommit(false);
            try {
                clearTables(connection);
                saveUsers(connection, users);
                saveAuctions(connection, auctions);
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IOException("Unable to save database snapshot", ex);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id VARCHAR(255) PRIMARY KEY,
                    username VARCHAR(255) NOT NULL UNIQUE,
                    password_hash VARCHAR(255) NOT NULL,
                    role VARCHAR(32) NOT NULL,
                    auction_limit INT NOT NULL,
                    created_at BIGINT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS auctions (
                    id VARCHAR(255) PRIMARY KEY,
                    item_id VARCHAR(255) NOT NULL,
                    item_type VARCHAR(64) NOT NULL,
                    item_name VARCHAR(255) NOT NULL,
                    description CLOB NOT NULL,
                    extra_value VARCHAR(255),
                    image_url CLOB,
                    currency VARCHAR(16) NOT NULL,
                    starting_price DOUBLE PRECISION NOT NULL,
                    owner_id VARCHAR(255) NOT NULL,
                    owner_username VARCHAR(255) NOT NULL,
                    start_time BIGINT NOT NULL,
                    end_time BIGINT NOT NULL,
                    current_price DOUBLE PRECISION NOT NULL,
                    highest_bidder_id VARCHAR(255),
                    highest_bidder_username VARCHAR(255),
                    state VARCHAR(32) NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS bid_transactions (
                    auction_id VARCHAR(255) NOT NULL,
                    sequence_no INT NOT NULL,
                    bidder_id VARCHAR(255) NOT NULL,
                    bidder_username VARCHAR(255) NOT NULL,
                    amount DOUBLE PRECISION NOT NULL,
                    timestamp BIGINT NOT NULL,
                    PRIMARY KEY (auction_id, sequence_no)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS auto_bids (
                    auction_id VARCHAR(255) NOT NULL,
                    bidder_id VARCHAR(255) NOT NULL,
                    bidder_username VARCHAR(255) NOT NULL,
                    max_bid DOUBLE PRECISION NOT NULL,
                    increment_value DOUBLE PRECISION NOT NULL,
                    registration_time BIGINT NOT NULL,
                    PRIMARY KEY (auction_id, bidder_id)
                )
                """);
        }
    }

    private List<User> loadUsers(Connection connection) throws SQLException {
        List<User> users = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, username, password_hash, role, auction_limit, created_at
            FROM users
            ORDER BY username
            """);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                users.add(User.fromStoredHash(
                    rs.getString("id"),
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    User.Role.valueOf(rs.getString("role")),
                    rs.getInt("auction_limit"),
                    rs.getLong("created_at")
                ));
            }
        }
        return users;
    }

    private List<Auction> loadAuctions(Connection connection) throws SQLException {
        Map<String, List<BidTransaction>> bidHistoryByAuction = loadBidHistory(connection);
        Map<String, List<AutoBidConfig>> autoBidsByAuction = loadAutoBids(connection);
        List<Auction> auctions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, item_id, item_type, item_name, description, extra_value, image_url, currency,
                   starting_price, owner_id, owner_username, start_time, end_time, current_price,
                   highest_bidder_id, highest_bidder_username, state
            FROM auctions
            ORDER BY end_time
            """);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                Item item = ItemFactory.createItem(
                    rs.getString("item_type"),
                    rs.getString("item_id"),
                    rs.getString("item_name"),
                    rs.getString("description"),
                    rs.getDouble("starting_price"),
                    rs.getString("extra_value")
                );
                Auction auction = new Auction(
                    rs.getString("id"),
                    item,
                    rs.getString("owner_id"),
                    rs.getString("owner_username"),
                    rs.getLong("start_time"),
                    rs.getLong("end_time")
                );
                auction.restoreState(
                    rs.getString("image_url"),
                    rs.getString("currency"),
                    rs.getDouble("current_price"),
                    rs.getString("highest_bidder_id"),
                    rs.getString("highest_bidder_username"),
                    Auction.State.valueOf(rs.getString("state")),
                    bidHistoryByAuction.getOrDefault(rs.getString("id"), List.of()),
                    autoBidsByAuction.getOrDefault(rs.getString("id"), List.of())
                );
                auctions.add(auction);
            }
        }
        return auctions;
    }

    private Map<String, List<BidTransaction>> loadBidHistory(Connection connection) throws SQLException {
        Map<String, List<BidTransaction>> bidHistoryByAuction = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT auction_id, bidder_id, bidder_username, amount, timestamp
            FROM bid_transactions
            ORDER BY auction_id, sequence_no
            """);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                bidHistoryByAuction.computeIfAbsent(rs.getString("auction_id"), ignored -> new ArrayList<>())
                    .add(new BidTransaction(
                        rs.getString("bidder_id"),
                        rs.getString("bidder_username"),
                        rs.getDouble("amount"),
                        rs.getLong("timestamp")
                    ));
            }
        }
        return bidHistoryByAuction;
    }

    private Map<String, List<AutoBidConfig>> loadAutoBids(Connection connection) throws SQLException {
        Map<String, List<AutoBidConfig>> autoBidsByAuction = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT auction_id, bidder_id, bidder_username, max_bid, increment_value, registration_time
            FROM auto_bids
            ORDER BY auction_id, registration_time
            """);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                autoBidsByAuction.computeIfAbsent(rs.getString("auction_id"), ignored -> new ArrayList<>())
                    .add(new AutoBidConfig(
                        rs.getString("bidder_id"),
                        rs.getString("bidder_username"),
                        rs.getDouble("max_bid"),
                        rs.getDouble("increment_value"),
                        rs.getLong("registration_time")
                    ));
            }
        }
        return autoBidsByAuction;
    }

    private void clearTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM auto_bids");
            statement.execute("DELETE FROM bid_transactions");
            statement.execute("DELETE FROM auctions");
            statement.execute("DELETE FROM users");
        }
    }

    private void saveUsers(Connection connection, Collection<User> users) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO users (id, username, password_hash, role, auction_limit, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            for (User user : users) {
                statement.setString(1, user.getId());
                statement.setString(2, user.getUsername());
                statement.setString(3, user.getPasswordHashForStorage());
                statement.setString(4, user.getRole().name());
                statement.setInt(5, user.getAuctionLimit());
                statement.setLong(6, user.getCreatedAt());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void saveAuctions(Connection connection, Collection<Auction> auctions) throws SQLException {
        try (PreparedStatement auctionStatement = connection.prepareStatement("""
            INSERT INTO auctions (
                id, item_id, item_type, item_name, description, extra_value, image_url, currency,
                starting_price, owner_id, owner_username, start_time, end_time, current_price,
                highest_bidder_id, highest_bidder_username, state
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """);
             PreparedStatement bidStatement = connection.prepareStatement("""
                 INSERT INTO bid_transactions (auction_id, sequence_no, bidder_id, bidder_username, amount, timestamp)
                 VALUES (?, ?, ?, ?, ?, ?)
                 """);
             PreparedStatement autoBidStatement = connection.prepareStatement("""
                 INSERT INTO auto_bids (auction_id, bidder_id, bidder_username, max_bid, increment_value, registration_time)
                 VALUES (?, ?, ?, ?, ?, ?)
                 """)) {
            for (Auction auction : auctions) {
                auctionStatement.setString(1, auction.getId());
                auctionStatement.setString(2, auction.getItem().getId());
                auctionStatement.setString(3, auction.getItem().getType());
                auctionStatement.setString(4, auction.getItem().getName());
                auctionStatement.setString(5, auction.getItem().getDescription());
                auctionStatement.setString(6, auction.getItem().getExtraValue());
                auctionStatement.setString(7, auction.getImageFilename());
                auctionStatement.setString(8, auction.getCurrency());
                auctionStatement.setDouble(9, auction.getItem().getStartingPrice());
                auctionStatement.setString(10, auction.getOwnerId());
                auctionStatement.setString(11, auction.getOwnerUsername());
                auctionStatement.setLong(12, auction.getStartTime());
                auctionStatement.setLong(13, auction.getEndTime());
                auctionStatement.setDouble(14, auction.getCurrentPrice());
                auctionStatement.setString(15, auction.getHighestBidderId());
                auctionStatement.setString(16, auction.getHighestBidderUsername());
                auctionStatement.setString(17, auction.getState().name());
                auctionStatement.addBatch();

                int sequence = 0;
                for (BidTransaction bid : auction.getBidHistory()) {
                    bidStatement.setString(1, auction.getId());
                    bidStatement.setInt(2, sequence++);
                    bidStatement.setString(3, bid.getBidderId());
                    bidStatement.setString(4, bid.getBidderUsername());
                    bidStatement.setDouble(5, bid.getAmount());
                    bidStatement.setLong(6, bid.getTimestamp());
                    bidStatement.addBatch();
                }

                for (AutoBidConfig config : auction.getAutoBids()) {
                    autoBidStatement.setString(1, auction.getId());
                    autoBidStatement.setString(2, config.getBidderId());
                    autoBidStatement.setString(3, config.getBidderUsername());
                    autoBidStatement.setDouble(4, config.getMaxBid());
                    autoBidStatement.setDouble(5, config.getIncrement());
                    autoBidStatement.setLong(6, config.getRegistrationTime());
                    autoBidStatement.addBatch();
                }
            }
            auctionStatement.executeBatch();
            bidStatement.executeBatch();
            autoBidStatement.executeBatch();
        }
    }
}
