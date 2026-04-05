package com.auction.web.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private static final String JDBC_URL = "jdbc:h2:./auction_db;AUTO_SERVER=TRUE";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    public DatabaseManager() {
        try {
            Class.forName("org.h2.Driver");
            initDatabase();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
    }

    private void initDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "username VARCHAR(255) UNIQUE, " +
                    "password VARCHAR(255), " +
                    "role VARCHAR(50))");

            stmt.execute("CREATE TABLE IF NOT EXISTS auctions (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "item_id VARCHAR(255), " +
                    "item_type VARCHAR(100), " +
                    "item_name VARCHAR(255), " +
                    "description TEXT, " +
                    "starting_price DOUBLE, " +
                    "extra_info VARCHAR(255), " +
                    "owner_id VARCHAR(255), " +
                    "owner_username VARCHAR(255), " +
                    "start_time BIGINT, " +
                    "end_time BIGINT, " +
                    "current_price DOUBLE, " +
                    "highest_bidder_id VARCHAR(255), " +
                    "highest_bidder_username VARCHAR(255), " +
                    "image_filename VARCHAR(255), " +
                    "state VARCHAR(50))");

            stmt.execute("CREATE TABLE IF NOT EXISTS bids (" +
                    "auction_id VARCHAR(255), " +
                    "bidder_id VARCHAR(255), " +
                    "bidder_username VARCHAR(255), " +
                    "amount DOUBLE, " +
                    "timestamp BIGINT)");

            stmt.execute("CREATE TABLE IF NOT EXISTS autobids (" +
                    "auction_id VARCHAR(255), " +
                    "bidder_id VARCHAR(255), " +
                    "bidder_username VARCHAR(255), " +
                    "max_bid DOUBLE, " +
                    "increment DOUBLE, " +
                    "PRIMARY KEY (auction_id, bidder_id))");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
