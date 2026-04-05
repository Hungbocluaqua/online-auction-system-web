package com.auction.web.dto;

import com.auction.web.model.User;

public class UserView {
    private String id;
    private String username;
    private User.Role role;
    private int auctionLimit;
    private long createdAt;

    public UserView(String id, String username, User.Role role, int auctionLimit, long createdAt) {
        this.id = id;
        this.username = username;
        this.role = role;
        this.auctionLimit = auctionLimit;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
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
