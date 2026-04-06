package com.auction.web.dto;

import com.auction.web.model.User;

public class SessionView {
    private String token;
    private String userId;
    private String username;
    private User.Role role;
    private int auctionLimit;
    private long createdAt;
    private String csrfToken;
    private boolean twoFaEnabled;

    public SessionView(String token, String userId, String username, User.Role role, int auctionLimit, long createdAt) {
        this.token = token;
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.auctionLimit = auctionLimit;
        this.createdAt = createdAt;
    }

    public void setCsrfToken(String csrfToken) {
        this.csrfToken = csrfToken;
    }

    public String getCsrfToken() {
        return csrfToken;
    }

    public void setTwoFaEnabled(boolean twoFaEnabled) {
        this.twoFaEnabled = twoFaEnabled;
    }

    public boolean isTwoFaEnabled() {
        return twoFaEnabled;
    }

    public String getToken() {
        return token;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public User.Role getRole() {
        return role;
    }

    public int getAuctionLimit() {
        return auctionLimit;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
