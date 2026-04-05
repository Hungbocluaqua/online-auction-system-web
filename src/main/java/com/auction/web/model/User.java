package com.auction.web.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class User extends Entity {
    public enum Role {
        USER,
        ADMIN
    }

    private String username;
    private String passwordHash;
    private Role role;
    private int auctionLimit;
    private long createdAt;

    public User(String id, String username, String password, Role role) {
        super(id);
        this.username = username;
        this.passwordHash = hash(password);
        this.role = role;
        this.auctionLimit = defaultAuctionLimit(role);
        this.createdAt = System.currentTimeMillis();
    }

    public static User fromStoredHash(String id, String username, String passwordHash, Role role) {
        User user = new User(id, username, "", role);
        user.passwordHash = passwordHash;
        return user;
    }

    public static User fromStoredHash(String id, String username, String passwordHash, Role role, Integer auctionLimit) {
        return fromStoredHash(id, username, passwordHash, role, auctionLimit, null);
    }

    public static User fromStoredHash(String id, String username, String passwordHash, Role role, Integer auctionLimit, Long createdAt) {
        Role normalizedRole = role == Role.ADMIN ? Role.ADMIN : Role.USER;
        User user = fromStoredHash(id, username, passwordHash, normalizedRole);
        user.auctionLimit = auctionLimit == null ? defaultAuctionLimit(normalizedRole) : auctionLimit;
        user.createdAt = createdAt == null ? System.currentTimeMillis() : createdAt;
        return user;
    }

    public String getUsername() {
        return username;
    }

    public Role getRole() {
        return role;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHashForStorage() {
        return passwordHash;
    }

    public int getAuctionLimit() {
        return auctionLimit;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setAuctionLimit(int auctionLimit) {
        this.auctionLimit = auctionLimit;
    }

    public void setPassword(String password) {
        this.passwordHash = hash(password);
    }

    public boolean checkPassword(String candidate) {
        return passwordHash.equals(hash(candidate));
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hashed) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static int defaultAuctionLimit(Role role) {
        return role == Role.USER ? 3 : 0;
    }
}
