package com.auction.web.service;

import static org.junit.jupiter.api.Assertions.*;

import com.auction.web.dto.AuctionCreateRequest;
import com.auction.web.dto.AuctionUpdateRequest;
import com.auction.web.dto.AuctionView;
import com.auction.web.dto.SessionView;
import org.junit.jupiter.api.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuctionServiceIntegrationTest {

    private static AuctionService service;
    private static final String TEST_PASS = "Test@1234";

    @BeforeAll
    static void setup() {
        System.setProperty("test.db", "true");
        service = new AuctionService();
    }

    @AfterAll
    static void cleanup() {
        if (service != null) service.shutdown();
    }

    @Test
    @Order(1)
    void registerAndLogin() {
        SessionView session = service.register("testuser", TEST_PASS, "USER");
        assertNotNull(session);
        assertEquals("testuser", session.getUsername());

        SessionView login = service.login("testuser", TEST_PASS);
        assertNotNull(login);
        assertEquals("testuser", login.getUsername());
    }

    @Test
    @Order(2)
    void registerRejectsEmptyUsername() {
        assertThrows(IllegalArgumentException.class, () ->
            service.register("", TEST_PASS, "USER"));
    }

    @Test
    @Order(3)
    void registerRejectsShortPassword() {
        assertThrows(IllegalArgumentException.class, () ->
            service.register("newuser", "short", "USER"));
    }

    @Test
    @Order(4)
    void registerDefaultsRoleToUser() {
        SessionView session = service.register("roleuser", TEST_PASS, null);
        assertNotNull(session);
        assertEquals("USER", session.getRole().name());
    }

    @Test
    @Order(5)
    void loginRejectsInvalidCredentials() {
        assertThrows(IllegalArgumentException.class, () ->
            service.login("nonexistent", TEST_PASS));
    }

    @Test
    @Order(6)
    void getAuctionsReturnsList() {
        var auctions = service.getAuctions();
        assertNotNull(auctions);
    }

    @Test
    @Order(7)
    void createAuction() {
        SessionView seller = service.register("createseller", TEST_PASS, "USER");
        AuctionCreateRequest req = new AuctionCreateRequest();
        req.itemType = "ELECTRONICS";
        req.name = "Test Laptop";
        req.description = "A test laptop";
        req.startingPrice = 100;
        req.durationMinutes = 60;
        req.currency = "USD";
        req.extraInfo = "BrandX";

        AuctionView auction = service.createAuction(seller.getToken(), req);
        assertNotNull(auction);
        assertEquals("Test Laptop", auction.getItemName());
        assertEquals("RUNNING", auction.getState().name());
    }

    @Test
    @Order(8)
    void createAuctionRejectsZeroDuration() {
        SessionView seller = service.register("zerodurseller", TEST_PASS, "USER");
        AuctionCreateRequest req = new AuctionCreateRequest();
        req.itemType = "ELECTRONICS";
        req.name = "Test";
        req.description = "Test";
        req.startingPrice = 100;
        req.durationMinutes = 0;

        assertThrows(IllegalArgumentException.class, () ->
            service.createAuction(seller.getToken(), req));
    }

    @Test
    @Order(9)
    void createAuctionRejectsNegativePrice() {
        SessionView seller = service.register("negpriceseller", TEST_PASS, "USER");
        AuctionCreateRequest req = new AuctionCreateRequest();
        req.itemType = "ELECTRONICS";
        req.name = "Test";
        req.description = "Test";
        req.startingPrice = -10;
        req.durationMinutes = 60;

        assertThrows(IllegalArgumentException.class, () ->
            service.createAuction(seller.getToken(), req));
    }

    @Test
    @Order(10)
    void deleteAccountCascades() {
        SessionView session = service.register("cascade_user", TEST_PASS, "USER");

        AuctionCreateRequest req = new AuctionCreateRequest();
        req.itemType = "ELECTRONICS";
        req.name = "Cascade Test";
        req.description = "Test";
        req.startingPrice = 50;
        req.durationMinutes = 60;
        service.createAuction(session.getToken(), req);

        String result = service.deleteAccount(session.getToken(), TEST_PASS);
        assertEquals("Account deleted", result);

        assertThrows(SecurityException.class, () -> service.requireUser(session.getToken()));
    }

    @Test
    @Order(11)
    void createAuctionRejectsExcessivePrice() {
        SessionView seller = service.register("pricecapseller", TEST_PASS, "USER");
        AuctionCreateRequest req = new AuctionCreateRequest();
        req.itemType = "ELECTRONICS";
        req.name = "Too Expensive";
        req.description = "Test";
        req.startingPrice = 1_000_000_000_001d;
        req.durationMinutes = 60;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            service.createAuction(seller.getToken(), req));
        assertTrue(ex.getMessage().contains("must not exceed"));
    }

    @Test
    @Order(12)
    void updateAuctionPersistsBuyNowAndReservePrices() {
        SessionView seller = service.register("updateseller", TEST_PASS, "USER");
        AuctionCreateRequest create = new AuctionCreateRequest();
        create.itemType = "ELECTRONICS";
        create.name = "Update Target";
        create.description = "Original";
        create.startingPrice = 100;
        create.durationMinutes = 60;

        AuctionView created = service.createAuction(seller.getToken(), create);

        AuctionUpdateRequest update = new AuctionUpdateRequest();
        update.itemType = "ELECTRONICS";
        update.name = "Updated Target";
        update.description = "Updated";
        update.startingPrice = 150;
        update.buyItNowPrice = 250;
        update.reservePrice = 200;
        update.durationMinutes = 120;
        update.currency = "USD";

        AuctionView updated = service.updateAuction(seller.getToken(), created.getId(), update);
        assertEquals(250, updated.getBuyItNowPrice());
        assertEquals(200, updated.getReservePrice());
        assertEquals(150, updated.getStartingPrice());
    }

    @Test
    @Order(13)
    void ownerCanDeleteAuction() {
        SessionView seller = service.register("deleteowner", TEST_PASS, "USER");
        AuctionCreateRequest req = new AuctionCreateRequest();
        req.itemType = "ELECTRONICS";
        req.name = "Delete Me";
        req.description = "Test";
        req.startingPrice = 75;
        req.durationMinutes = 60;

        AuctionView auction = service.createAuction(seller.getToken(), req);
        service.deleteAuction(seller.getToken(), auction.getId());

        assertThrows(IllegalArgumentException.class, () -> service.getAuction(auction.getId()));
    }

    @Test
    @Order(14)
    void adminCanDeleteAnotherUsersAuction() {
        SessionView seller = service.register("deleteotherseller", TEST_PASS, "USER");
        SessionView admin = service.register("deleteadmin", TEST_PASS, "ADMIN");

        AuctionCreateRequest req = new AuctionCreateRequest();
        req.itemType = "ELECTRONICS";
        req.name = "Admin Delete";
        req.description = "Test";
        req.startingPrice = 90;
        req.durationMinutes = 60;

        AuctionView auction = service.createAuction(seller.getToken(), req);
        service.deleteAuction(admin.getToken(), auction.getId());

        assertThrows(IllegalArgumentException.class, () -> service.getAuction(auction.getId()));
    }
}
