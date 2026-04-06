-- V1__Initial_schema.sql
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(255) PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'USER',
    auction_limit INT DEFAULT 5,
    created_at BIGINT DEFAULT 0,
    email VARCHAR(255),
    email_verified BOOLEAN DEFAULT FALSE,
    failed_login_attempts INT DEFAULT 0,
    locked_until BIGINT DEFAULT 0,
    totp_secret VARCHAR(255),
    totp_enabled BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);

CREATE TABLE IF NOT EXISTS sessions (
    token VARCHAR(255) PRIMARY KEY,
    username VARCHAR(255),
    created_at BIGINT,
    last_active_at BIGINT,
    csrf_token VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_sessions_username ON sessions(username);

CREATE TABLE IF NOT EXISTS auctions (
    id VARCHAR(255) PRIMARY KEY,
    item_id VARCHAR(255),
    item_type VARCHAR(100),
    item_name VARCHAR(255),
    description TEXT,
    starting_price DOUBLE PRECISION,
    extra_info VARCHAR(255),
    owner_id VARCHAR(255),
    owner_username VARCHAR(255),
    start_time BIGINT,
    end_time BIGINT,
    current_price DOUBLE PRECISION,
    highest_bidder_id VARCHAR(255),
    highest_bidder_username VARCHAR(255),
    image_filename VARCHAR(255),
    currency VARCHAR(10) DEFAULT 'USD',
    state VARCHAR(50),
    fulfillment_status VARCHAR(50) DEFAULT 'none',
    category VARCHAR(100) DEFAULT 'general'
);

CREATE INDEX IF NOT EXISTS idx_auctions_state ON auctions(state);
CREATE INDEX IF NOT EXISTS idx_auctions_owner ON auctions(owner_id);
CREATE INDEX IF NOT EXISTS idx_auctions_category ON auctions(category);
CREATE INDEX IF NOT EXISTS idx_auctions_end_time ON auctions(end_time);

CREATE TABLE IF NOT EXISTS bids (
    id VARCHAR(255) PRIMARY KEY,
    auction_id VARCHAR(255),
    bidder_id VARCHAR(255),
    bidder_username VARCHAR(255),
    amount DOUBLE PRECISION,
    timestamp BIGINT
);

CREATE INDEX IF NOT EXISTS idx_bids_auction ON bids(auction_id);
CREATE INDEX IF NOT EXISTS idx_bids_bidder ON bids(bidder_id);
CREATE INDEX IF NOT EXISTS idx_bids_auction_amount ON bids(auction_id, amount);

CREATE TABLE IF NOT EXISTS autobids (
    auction_id VARCHAR(255),
    bidder_id VARCHAR(255),
    bidder_username VARCHAR(255),
    max_bid DOUBLE PRECISION,
    increment DOUBLE PRECISION,
    PRIMARY KEY (auction_id, bidder_id)
);

CREATE INDEX IF NOT EXISTS idx_autobids_auction ON autobids(auction_id);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    token VARCHAR(255) PRIMARY KEY,
    username VARCHAR(255),
    expires_at BIGINT,
    used BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_reset_username ON password_reset_tokens(username);

CREATE TABLE IF NOT EXISTS verification_tokens (
    token VARCHAR(255) PRIMARY KEY,
    username VARCHAR(255),
    email VARCHAR(255),
    expires_at BIGINT,
    used BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_verify_username ON verification_tokens(username);

CREATE TABLE IF NOT EXISTS notifications (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255),
    type VARCHAR(50),
    message TEXT,
    is_read BOOLEAN DEFAULT FALSE,
    created_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_is_read ON notifications(user_id, is_read);

CREATE TABLE IF NOT EXISTS payments (
    id VARCHAR(255) PRIMARY KEY,
    auction_id VARCHAR(255),
    buyer_id VARCHAR(255),
    buyer_username VARCHAR(255),
    amount DOUBLE PRECISION,
    status VARCHAR(50) DEFAULT 'pending',
    created_at BIGINT,
    stripe_payment_intent_id VARCHAR(255),
    invoice_url TEXT
);

CREATE INDEX IF NOT EXISTS idx_payments_auction ON payments(auction_id);
CREATE INDEX IF NOT EXISTS idx_payments_buyer ON payments(buyer_id);

CREATE TABLE IF NOT EXISTS disputes (
    id VARCHAR(255) PRIMARY KEY,
    auction_id VARCHAR(255),
    reporter_id VARCHAR(255),
    reporter_username VARCHAR(255),
    reason TEXT,
    status VARCHAR(50) DEFAULT 'open',
    resolution TEXT,
    created_at BIGINT
);

CREATE INDEX IF NOT EXISTS idx_disputes_status ON disputes(status);
CREATE INDEX IF NOT EXISTS idx_disputes_auction ON disputes(auction_id);

CREATE TABLE IF NOT EXISTS watchlist (
    user_id VARCHAR(255),
    auction_id VARCHAR(255),
    added_at BIGINT,
    PRIMARY KEY (user_id, auction_id)
);

CREATE INDEX IF NOT EXISTS idx_watchlist_user ON watchlist(user_id);

CREATE TABLE IF NOT EXISTS invoices (
    id VARCHAR(255) PRIMARY KEY,
    payment_id VARCHAR(255),
    auction_id VARCHAR(255),
    buyer_id VARCHAR(255),
    buyer_username VARCHAR(255),
    seller_username VARCHAR(255),
    item_name VARCHAR(255),
    amount DOUBLE PRECISION,
    currency VARCHAR(10),
    created_at BIGINT,
    pdf_url TEXT
);

CREATE INDEX IF NOT EXISTS idx_invoices_buyer ON invoices(buyer_id);
