package com.auction.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.auction.web.dto.AuctionCreateRequest;
import com.auction.web.dto.SessionView;
import com.auction.web.persistence.DatabaseSnapshotStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuctionServiceTest {

    @Test
    void databaseStorePersistsRegisteredUserAndAuction() throws Exception {
        Path tempDir = Files.createTempDirectory("auction-service-db");
        AuctionService service = new AuctionService(DatabaseSnapshotStore.forFile(tempDir.resolve("auction-db")), false);

        SessionView registered = service.register("phase1-user", "password123", "USER");
        AuctionCreateRequest request = new AuctionCreateRequest();
        request.itemType = "BOOK";
        request.name = "Distributed Systems";
        request.description = "Reference book";
        request.startingPrice = 100;
        request.currency = "USD";
        request.extraInfo = "Tanenbaum";
        request.imageUrl = "";
        request.durationMinutes = 60;
        String auctionId = service.createAuction(registered.getToken(), request).getId();

        AuctionService reloaded = new AuctionService(DatabaseSnapshotStore.forFile(tempDir.resolve("auction-db")), false);
        SessionView loggedIn = reloaded.login("phase1-user", "password123");

        assertNotNull(loggedIn);
        assertTrue(reloaded.getAuctions().stream().anyMatch(auction -> auction.getId().equals(auctionId)));
        assertTrue(reloaded.getAuctions().stream().anyMatch(auction -> "USD".equals(auction.getCurrency())));
    }

    @Test
    void placedBidPersistsAcrossReload() throws Exception {
        Path tempDir = Files.createTempDirectory("auction-service-db-bid");
        AuctionService service = new AuctionService(DatabaseSnapshotStore.forFile(tempDir.resolve("auction-db")), false);

        SessionView seller = service.register("seller-phase1", "password123", "USER");
        SessionView bidder = service.register("bidder-phase1", "password123", "USER");
        AuctionCreateRequest request = new AuctionCreateRequest();
        request.itemType = "ELECTRONICS";
        request.name = "Camera";
        request.description = "Mirrorless";
        request.startingPrice = 200;
        request.currency = "USD";
        request.extraInfo = "Sony";
        request.imageUrl = "";
        request.durationMinutes = 60;
        String auctionId = service.createAuction(seller.getToken(), request).getId();

        service.placeBid(bidder.getToken(), auctionId, 250);

        AuctionService reloaded = new AuctionService(DatabaseSnapshotStore.forFile(tempDir.resolve("auction-db")), false);
        var auction = reloaded.getAuction(auctionId);

        assertEquals(250, auction.getCurrentPrice());
        assertEquals("bidder-phase1", auction.getHighestBidderUsername());
        assertFalse(auction.getBidHistory().isEmpty());
    }

    @Test
    void accountDeletionIsBlockedForUsersWithAuctionHistory() throws Exception {
        Path tempDir = Files.createTempDirectory("auction-service-db-delete");
        AuctionService service = new AuctionService(DatabaseSnapshotStore.forFile(tempDir.resolve("auction-db")), false);

        SessionView seller = service.register("seller-guard", "password123", "USER");
        AuctionCreateRequest request = new AuctionCreateRequest();
        request.itemType = "ART";
        request.name = "Canvas";
        request.description = "Painting";
        request.startingPrice = 300;
        request.currency = "USD";
        request.extraInfo = "Unknown";
        request.imageUrl = "";
        request.durationMinutes = 60;
        service.createAuction(seller.getToken(), request);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.deleteAccount(seller.getToken(), "password123"));
        assertTrue(ex.getMessage().contains("Delete your auctions"));
    }
}
