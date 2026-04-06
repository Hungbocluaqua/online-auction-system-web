package com.auction.web.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

public class DatabaseManager {
    private static final String H2_PROD_URL = "jdbc:h2:file:./data/auction-db;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;LOCK_MODE=3;CACHE_SIZE=131072";
    private static final String H2_TEST_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASSWORD = "";
    private HikariDataSource dataSource;
    private final boolean isPostgres;

    public DatabaseManager() {
        this(Boolean.getBoolean("test.db"));
    }

    public DatabaseManager(boolean testMode) {
        this(testMode, null, null, null);
    }

    public DatabaseManager(boolean testMode, String pgUrl, String pgUser, String pgPass) {
        String jdbcUrl;
        String user;
        String pass;

        if (pgUrl != null && !pgUrl.isBlank()) {
            jdbcUrl = pgUrl;
            user = pgUser != null ? pgUser : "auction";
            pass = pgPass != null ? pgPass : "";
            isPostgres = true;
        } else if (testMode) {
            jdbcUrl = H2_TEST_URL;
            user = USER;
            pass = PASSWORD;
            isPostgres = false;
        } else {
            jdbcUrl = H2_PROD_URL;
            user = USER;
            pass = PASSWORD;
            isPostgres = false;
        }

        try {
            if (isPostgres) {
                Class.forName("org.postgresql.Driver");
            } else {
                Class.forName("org.h2.Driver");
            }

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(user);
            config.setPassword(pass);
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.setConnectionTimeout(10000);
            config.setIdleTimeout(300000);
            config.setMaxLifetime(600000);
            config.setPoolName("AuctionPool");

            if (isPostgres) {
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                config.addDataSourceProperty("useServerPrepStmts", "true");
            }

            this.dataSource = new HikariDataSource(config);

            if (isPostgres) {
                runFlywayMigrations(jdbcUrl, user, pass);
            } else {
                initH2Schema();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    private void runFlywayMigrations(String jdbcUrl, String user, String pass) {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, user, pass)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();
    }

    private void initH2Schema() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "username VARCHAR(255) UNIQUE, " +
                    "password VARCHAR(255), " +
                    "role VARCHAR(50))");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_users_username ON users(username)"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE users ADD COLUMN auction_limit INT DEFAULT 5"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE users ADD COLUMN created_at BIGINT DEFAULT 0"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE users ADD COLUMN email VARCHAR(255)"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE users ADD COLUMN email_verified BOOLEAN DEFAULT FALSE"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE users ADD COLUMN failed_login_attempts INT DEFAULT 0"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE users ADD COLUMN locked_until BIGINT DEFAULT 0"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE users ADD COLUMN totp_secret VARCHAR(255)"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE users ADD COLUMN totp_enabled BOOLEAN DEFAULT FALSE"); } catch (SQLException ignored) {}

            stmt.execute("CREATE TABLE IF NOT EXISTS sessions (" +
                    "token VARCHAR(255) PRIMARY KEY, " +
                    "username VARCHAR(255), " +
                    "created_at BIGINT, " +
                    "last_active_at BIGINT, " +
                    "csrf_token VARCHAR(255))");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_username ON sessions(username)"); } catch (SQLException ignored) {}

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
                    "currency VARCHAR(10) DEFAULT 'USD', " +
                    "state VARCHAR(50), " +
                    "fulfillment_status VARCHAR(50) DEFAULT 'none', " +
                    "category VARCHAR(100) DEFAULT 'general')");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_auctions_state ON auctions(state)"); } catch (SQLException ignored) {}
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_auctions_owner ON auctions(owner_id)"); } catch (SQLException ignored) {}
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_auctions_category ON auctions(category)"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE auctions ADD COLUMN IF NOT EXISTS currency VARCHAR(10) DEFAULT 'USD'"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE auctions ADD COLUMN IF NOT EXISTS fulfillment_status VARCHAR(50) DEFAULT 'none'"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE auctions ADD COLUMN IF NOT EXISTS category VARCHAR(100) DEFAULT 'general'"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE auctions ADD COLUMN IF NOT EXISTS buy_it_now_price DOUBLE"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE auctions ADD COLUMN IF NOT EXISTS reserve_price DOUBLE"); } catch (SQLException ignored) {}

            stmt.execute("CREATE TABLE IF NOT EXISTS bids (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "auction_id VARCHAR(255), " +
                    "bidder_id VARCHAR(255), " +
                    "bidder_username VARCHAR(255), " +
                    "amount DOUBLE, " +
                    "timestamp BIGINT)");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_bids_auction ON bids(auction_id)"); } catch (SQLException ignored) {}
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_bids_bidder ON bids(bidder_id)"); } catch (SQLException ignored) {}
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_bids_auction_amount ON bids(auction_id, amount)"); } catch (SQLException ignored) {}

            stmt.execute("CREATE TABLE IF NOT EXISTS autobids (" +
                    "auction_id VARCHAR(255), " +
                    "bidder_id VARCHAR(255), " +
                    "bidder_username VARCHAR(255), " +
                    "max_bid DOUBLE, " +
                    "increment DOUBLE, " +
                    "PRIMARY KEY (auction_id, bidder_id))");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_autobids_auction ON autobids(auction_id)"); } catch (SQLException ignored) {}

            stmt.execute("CREATE TABLE IF NOT EXISTS password_reset_tokens (" +
                    "token VARCHAR(255) PRIMARY KEY, " +
                    "username VARCHAR(255), " +
                    "expires_at BIGINT, " +
                    "used BOOLEAN DEFAULT FALSE)");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_reset_username ON password_reset_tokens(username)"); } catch (SQLException ignored) {}

            stmt.execute("CREATE TABLE IF NOT EXISTS verification_tokens (" +
                    "token VARCHAR(255) PRIMARY KEY, " +
                    "username VARCHAR(255), " +
                    "email VARCHAR(255), " +
                    "expires_at BIGINT, " +
                    "used BOOLEAN DEFAULT FALSE)");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_verify_username ON verification_tokens(username)"); } catch (SQLException ignored) {}

            stmt.execute("CREATE TABLE IF NOT EXISTS notifications (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "user_id VARCHAR(255), " +
                    "type VARCHAR(50), " +
                    "message TEXT, " +
                    "is_read BOOLEAN DEFAULT FALSE, " +
                    "created_at BIGINT)");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id)"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE notifications ADD COLUMN IF NOT EXISTS is_read BOOLEAN DEFAULT FALSE"); } catch (SQLException ignored) {}

            stmt.execute("CREATE TABLE IF NOT EXISTS payments (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "auction_id VARCHAR(255), " +
                    "buyer_id VARCHAR(255), " +
                    "buyer_username VARCHAR(255), " +
                    "amount DOUBLE, " +
                    "status VARCHAR(50) DEFAULT 'pending', " +
                    "created_at BIGINT, " +
                    "stripe_payment_intent_id VARCHAR(255), " +
                    "invoice_url TEXT)");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_payments_auction ON payments(auction_id)"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE payments ADD COLUMN IF NOT EXISTS stripe_payment_intent_id VARCHAR(255)"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE payments ADD COLUMN IF NOT EXISTS invoice_url TEXT"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE payments ADD COLUMN IF NOT EXISTS zalopay_trans_id VARCHAR(255)"); } catch (SQLException ignored) {}

            stmt.execute("CREATE TABLE IF NOT EXISTS refunds (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "payment_id VARCHAR(255), " +
                    "auction_id VARCHAR(255), " +
                    "requester_id VARCHAR(255), " +
                    "requester_username VARCHAR(255), " +
                    "amount DOUBLE, " +
                    "reason TEXT, " +
                    "status VARCHAR(50) DEFAULT 'pending', " +
                    "zalopay_refund_id VARCHAR(255), " +
                    "created_at BIGINT)");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_refunds_payment ON refunds(payment_id)"); } catch (SQLException ignored) {}

            stmt.execute("CREATE TABLE IF NOT EXISTS disputes (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "auction_id VARCHAR(255), " +
                    "reporter_id VARCHAR(255), " +
                    "reporter_username VARCHAR(255), " +
                    "reason TEXT, " +
                    "status VARCHAR(50) DEFAULT 'open', " +
                    "resolution TEXT, " +
                    "created_at BIGINT)");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_disputes_status ON disputes(status)"); } catch (SQLException ignored) {}

            stmt.execute("CREATE TABLE IF NOT EXISTS watchlist (" +
                    "user_id VARCHAR(255), " +
                    "auction_id VARCHAR(255), " +
                    "added_at BIGINT, " +
                    "PRIMARY KEY (user_id, auction_id))");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_watchlist_user ON watchlist(user_id)"); } catch (SQLException ignored) {}

            stmt.execute("CREATE TABLE IF NOT EXISTS invoices (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "payment_id VARCHAR(255), " +
                    "auction_id VARCHAR(255), " +
                    "buyer_id VARCHAR(255), " +
                    "buyer_username VARCHAR(255), " +
                    "seller_username VARCHAR(255), " +
                    "item_name VARCHAR(255), " +
                    "amount DOUBLE, " +
                    "currency VARCHAR(10), " +
                    "created_at BIGINT, " +
                    "pdf_url TEXT)");
            try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_invoices_buyer ON invoices(buyer_id)"); } catch (SQLException ignored) {}
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public boolean isPostgres() {
        return isPostgres;
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            if (!isPostgres) {
                try (Connection conn = getConnection()) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("SHUTDOWN");
                    }
                } catch (SQLException ignored) {}
            }
            dataSource.close();
        }
    }

    public Map<String, Object> getPoolStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        if (dataSource != null && !dataSource.isClosed()) {
            stats.put("activeConnections", dataSource.getHikariPoolMXBean().getActiveConnections());
            stats.put("idleConnections", dataSource.getHikariPoolMXBean().getIdleConnections());
            stats.put("totalConnections", dataSource.getHikariPoolMXBean().getTotalConnections());
            stats.put("waitingThreads", dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
            stats.put("maxPoolSize", dataSource.getMaximumPoolSize());
        } else {
            stats.put("status", "closed");
        }
        return stats;
    }
}
