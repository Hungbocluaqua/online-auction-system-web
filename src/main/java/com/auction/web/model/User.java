package com.auction.web.model;

import org.mindrot.jbcrypt.BCrypt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class User extends Entity {
    public enum Role { USER, ADMIN }

    private String username;
    private String passwordHash;
    private Role role;
    private int auctionLimit;
    private long createdAt;
    private String email;
    private boolean emailVerified;
    private int failedLoginAttempts;
    private long lockedUntil;
    private String totpSecret;
    private boolean totpEnabled;

    public User(String id, String username, String password, Role role) {
        super(id);
        this.username = username;
        this.passwordHash = hash(password);
        this.role = role;
        this.auctionLimit = defaultAuctionLimit(role);
        this.createdAt = System.currentTimeMillis();
        this.totpSecret = null;
        this.totpEnabled = false;
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
        User user = new User(id, username, "", normalizedRole);
        user.passwordHash = passwordHash;
        user.auctionLimit = auctionLimit == null ? defaultAuctionLimit(normalizedRole) : auctionLimit;
        user.createdAt = createdAt == null ? System.currentTimeMillis() : createdAt;
        return user;
    }

    public static User fromStoredHash(String id, String username, String passwordHash, Role role, Integer auctionLimit, Long createdAt,
                                       String email, boolean emailVerified, int failedLoginAttempts, long lockedUntil) {
        return fromStoredHash(id, username, passwordHash, role, auctionLimit, createdAt, email, emailVerified, failedLoginAttempts, lockedUntil, null, false);
    }

    public static User fromStoredHash(String id, String username, String passwordHash, Role role, Integer auctionLimit, Long createdAt,
                                       String email, boolean emailVerified, int failedLoginAttempts, long lockedUntil,
                                       String totpSecret, boolean totpEnabled) {
        Role normalizedRole = role == Role.ADMIN ? Role.ADMIN : Role.USER;
        User user = new User(id, username, "", normalizedRole);
        user.passwordHash = passwordHash;
        user.auctionLimit = auctionLimit == null ? defaultAuctionLimit(normalizedRole) : auctionLimit;
        user.createdAt = createdAt == null ? System.currentTimeMillis() : createdAt;
        user.email = email;
        user.emailVerified = emailVerified;
        user.failedLoginAttempts = failedLoginAttempts;
        user.lockedUntil = lockedUntil;
        user.totpSecret = totpSecret;
        user.totpEnabled = totpEnabled;
        return user;
    }

    public String getUsername() { return username; }
    public Role getRole() { return role; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHashForStorage() { return passwordHash; }
    public int getAuctionLimit() { return auctionLimit; }
    public long getCreatedAt() { return createdAt; }
    public void setAuctionLimit(int auctionLimit) { this.auctionLimit = auctionLimit; }
    public void setPassword(String password) { this.passwordHash = hash(password); }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public void setFailedLoginAttempts(int failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; }
    public long getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(long lockedUntil) { this.lockedUntil = lockedUntil; }
    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }
    public boolean isTotpEnabled() { return totpEnabled; }
    public void setTotpEnabled(boolean totpEnabled) { this.totpEnabled = totpEnabled; }

    public boolean isLocked() {
        return lockedUntil > 0 && System.currentTimeMillis() < lockedUntil;
    }

    public void resetFailedAttempts() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = 0;
    }

    public void recordFailedLogin() {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= 5) {
            this.lockedUntil = System.currentTimeMillis() + 15 * 60 * 1000L;
        }
    }

    public boolean checkPassword(String candidate) {
        if (passwordHash.startsWith("$2a$") || passwordHash.startsWith("$2b$")) {
            return BCrypt.checkpw(candidate, passwordHash);
        }
        boolean matches = passwordHash.equals(sha256(candidate));
        if (matches) {
            this.passwordHash = hash(candidate);
        }
        return matches;
    }

    private static String hash(String value) {
        return BCrypt.hashpw(value, BCrypt.gensalt(12));
    }

    private static String sha256(String value) {
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
        return role == Role.USER ? 3 : Integer.MAX_VALUE;
    }

    public static boolean isValidPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) return false;
        boolean hasUpper = false, hasLower = false, hasDigit = false, hasSpecial = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSpecial = true;
        }
        return hasUpper && hasLower && hasDigit && hasSpecial;
    }

    public static String getPasswordPolicyMessage() {
        return "Password must be 8-128 characters with at least one uppercase, one lowercase, one digit, and one special character";
    }
}
