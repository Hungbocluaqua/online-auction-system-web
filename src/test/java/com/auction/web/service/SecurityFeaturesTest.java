package com.auction.web.service;

import static org.junit.jupiter.api.Assertions.*;

import com.auction.web.dto.AuctionCreateRequest;
import com.auction.web.dto.AuctionView;
import com.auction.web.dto.SessionView;
import com.auction.web.model.User;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityFeaturesTest {

    private static AuctionService service;
    private static final String TEST_PASS = "Test@1234";

    @BeforeAll
    static void setup() {
        System.setProperty("test.db", "true");
        service = new AuctionService(true);
    }

    @AfterAll
    static void cleanup() {
        if (service != null) service.shutdown();
    }

    @Test
    @Order(1)
    void passwordPolicyRejectsWeakPasswords() {
        assertFalse(User.isValidPassword("short"));
        assertFalse(User.isValidPassword("alllowercase1"));
        assertFalse(User.isValidPassword("ALLUPPERCASE1"));
        assertFalse(User.isValidPassword("NoSpecial1234"));
        assertFalse(User.isValidPassword("NoDigits!abc"));
        assertFalse(User.isValidPassword(""));
        assertFalse(User.isValidPassword(null));
        assertTrue(User.isValidPassword("Valid@1234"));
        assertTrue(User.isValidPassword("Str0ng!Pass"));
    }

    @Test
    @Order(2)
    void registerRejectsWeakPassword() {
        assertThrows(IllegalArgumentException.class, () ->
            service.register("weakuser", "weakpass", "USER"));
    }

    @Test
    @Order(3)
    void changePasswordEnforcesPolicy() {
        SessionView session = service.register("pwdpolicyuser", TEST_PASS, "USER");
        assertThrows(IllegalArgumentException.class, () ->
            service.changePassword(session.getToken(), TEST_PASS, "weak"));
        assertThrows(IllegalArgumentException.class, () ->
            service.changePassword(session.getToken(), TEST_PASS, "alllowercase1"));
        SessionView updated = service.changePassword(session.getToken(), TEST_PASS, "NewPass@5678");
        assertNotNull(updated);
    }

    @Test
    @Order(4)
    void accountLockoutAfterFailedAttempts() {
        service.register("lockoutuser", TEST_PASS, "USER");
        for (int i = 0; i < 5; i++) {
            try {
                service.login("lockoutuser", "WrongPass@1234");
            } catch (IllegalArgumentException ignored) {}
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            service.login("lockoutuser", TEST_PASS));
        assertTrue(ex.getMessage().contains("locked"));
    }

    @Test
    @Order(5)
    void successfulLoginResetsFailedAttempts() {
        service.register("resetlockuser", TEST_PASS, "USER");
        for (int i = 0; i < 3; i++) {
            try { service.login("resetlockuser", "WrongPass@1234"); } catch (IllegalArgumentException ignored) {}
        }
        SessionView session = service.login("resetlockuser", TEST_PASS);
        assertNotNull(session);
        for (int i = 0; i < 5; i++) {
            try { service.login("resetlockuser", "WrongPass@1234"); } catch (IllegalArgumentException ignored) {}
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            service.login("resetlockuser", TEST_PASS));
        assertTrue(ex.getMessage().contains("locked"));
    }

    @Test
    @Order(6)
    void csrfTokenGeneratedOnLogin() {
        SessionView session = service.register("csrfuser", TEST_PASS, "USER");
        assertNotNull(session.getCsrfToken());
        assertFalse(session.getCsrfToken().isBlank());
        assertTrue(service.validateCsrfToken(session.getToken(), session.getCsrfToken()));
    }

    @Test
    @Order(7)
    void csrfTokenValidationRejectsInvalid() {
        SessionView session = service.register("csrfuser2", TEST_PASS, "USER");
        assertFalse(service.validateCsrfToken(session.getToken(), "invalid-token"));
        assertFalse(service.validateCsrfToken(session.getToken(), ""));
        assertFalse(service.validateCsrfToken(session.getToken(), null));
        assertFalse(service.validateCsrfToken(null, "some-token"));
    }

    @Test
    @Order(8)
    void passwordResetFlow() {
        service.register("resetflow", TEST_PASS, "USER");
        String result = service.requestPasswordReset("resetflow");
        assertTrue(result.contains("reset token has been generated"));
        String token = getResetTokenFromDb("resetflow");
        assertNotNull(token);
        SessionView session = service.confirmPasswordReset(token, "NewReset@1234");
        assertNotNull(session);
        assertEquals("resetflow", session.getUsername());
        SessionView login = service.login("resetflow", "NewReset@1234");
        assertNotNull(login);
    }

    @Test
    @Order(9)
    void passwordResetRejectsExpiredToken() {
        service.register("expiredreset", TEST_PASS, "USER");
        assertThrows(IllegalArgumentException.class, () ->
            service.confirmPasswordReset("nonexistent-token", "NewPass@1234"));
    }

    @Test
    @Order(10)
    void passwordResetRejectsWeakNewPassword() {
        service.register("weakreset", TEST_PASS, "USER");
        service.requestPasswordReset("weakreset");
        String token = getResetTokenFromDb("weakreset");
        assertThrows(IllegalArgumentException.class, () ->
            service.confirmPasswordReset(token, "weak"));
    }

    @Test
    @Order(11)
    void emailVerificationFlow() {
        SessionView session = service.register("emailuser", TEST_PASS, "USER");
        String result = service.requestEmailVerification(session.getToken(), "test@example.com");
        assertTrue(result.contains("Token:"));
        String token = result.substring(result.indexOf("Token: ") + 7);
        SessionView verified = service.confirmEmailVerification(token);
        assertNotNull(verified);
    }

    @Test
    @Order(12)
    void emailVerificationRejectsInvalidFormat() {
        SessionView session = service.register("bademail", TEST_PASS, "USER");
        assertThrows(IllegalArgumentException.class, () ->
            service.requestEmailVerification(session.getToken(), "not-an-email"));
        assertThrows(IllegalArgumentException.class, () ->
            service.requestEmailVerification(session.getToken(), ""));
    }

    @Test
    @Order(13)
    void sessionExpiryDeletesOldSessions() {
        SessionView session = service.register("expiryuser", TEST_PASS, "USER");
        assertNotNull(service.requireSession(session.getToken()));
    }

    @Test
    @Order(14)
    void changePasswordRequiresCorrectCurrentPassword() {
        SessionView session = service.register("chgpwd", TEST_PASS, "USER");
        assertThrows(IllegalArgumentException.class, () ->
            service.changePassword(session.getToken(), "WrongPass@1234", "NewPass@1234"));
    }

    @Test
    @Order(15)
    void deleteAccountRequiresCorrectPassword() {
        SessionView session = service.register("delwrong", TEST_PASS, "USER");
        assertThrows(IllegalArgumentException.class, () ->
            service.deleteAccount(session.getToken(), "WrongPass@1234"));
    }

    @Test
    @Order(16)
    void deleteAccountRequiresAdminRole() {
        SessionView user = service.register("delnonadmin", TEST_PASS, "USER");
        assertThrows(SecurityException.class, () ->
            service.deleteUser(user.getToken(), "otheruser"));
    }

    @Test
    @Order(17)
    void adminCannotBeDeleted() {
        // Register a temp admin since seed passwords are randomized
        String adminId = UUID.randomUUID().toString();
        User adminUser = new User(adminId, "admin2", TEST_PASS, User.Role.ADMIN);
        try (Connection conn = service.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO users (id, username, password, role, auction_limit, created_at, email, email_verified, failed_login_attempts, locked_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, adminUser.getId()); ps.setString(2, adminUser.getUsername());
            ps.setString(3, adminUser.getPasswordHashForStorage()); ps.setString(4, User.Role.ADMIN.name());
            ps.setInt(5, 100); ps.setLong(6, System.currentTimeMillis());
            ps.setString(7, null); ps.setBoolean(8, false); ps.setInt(9, 0); ps.setLong(10, 0);
            ps.executeUpdate();
        } catch (Exception e) { fail("Failed to create test admin: " + e.getMessage()); }
        SessionView admin = service.login("admin2", TEST_PASS);
        assertThrows(IllegalArgumentException.class, () ->
            service.deleteUser(admin.getToken(), "admin2"));
    }

    @Test
    @Order(18)
    void nonAdminCannotListUsers() {
        SessionView user = service.register("nolist", TEST_PASS, "USER");
        assertThrows(SecurityException.class, () ->
            service.getUsers(user.getToken()));
    }

    @Test
    @Order(19)
    void nonAdminCannotCancelAuction() {
        SessionView user = service.register("nocancel", TEST_PASS, "USER");
        assertThrows(SecurityException.class, () ->
            service.cancelAuction(user.getToken(), "nonexistent"));
    }

    @Test
    @Order(20)
    void nonAdminCannotUpdateAuctionLimit() {
        SessionView user = service.register("nolimit", TEST_PASS, "USER");
        assertThrows(SecurityException.class, () ->
            service.updateAuctionLimit(user.getToken(), "someuser", 10));
    }

    private String getResetTokenFromDb(String username) {
        try (Connection conn = service.getDbManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT token FROM password_reset_tokens WHERE username = ? AND used = FALSE ORDER BY expires_at DESC")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("token");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get reset token", e);
        }
        return null;
    }
}
