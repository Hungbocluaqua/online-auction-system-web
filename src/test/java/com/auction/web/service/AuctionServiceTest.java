package com.auction.web.service;

import static org.junit.jupiter.api.Assertions.*;

import com.auction.web.model.Auction;
import com.auction.web.model.BidTransaction;
import com.auction.web.model.Item;
import com.auction.web.model.ItemFactory;
import com.auction.web.model.User;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

class AuctionServiceTest {

    @Test
    void userPasswordHashingWithBcrypt() {
        User user = new User("1", "testuser", "password123", User.Role.USER);
        assertTrue(user.getPasswordHashForStorage().startsWith("$2a$"));
    }

    @Test
    void userPasswordCheck() {
        User user = new User("1", "testuser", "password123", User.Role.USER);
        assertTrue(user.checkPassword("password123"));
        assertFalse(user.checkPassword("wrongpassword"));
    }

    @Test
    void userAdminLimitIsUnlimited() {
        User admin = new User("1", "admin", "password", User.Role.ADMIN);
        assertEquals(Integer.MAX_VALUE, admin.getAuctionLimit());
    }

    @Test
    void userDefaultLimitForRegularUser() {
        User user = new User("1", "testuser", "password123", User.Role.USER);
        assertEquals(3, user.getAuctionLimit());
    }

    @Test
    void itemTypeStringsAreUppercase() {
        Item vehicle = ItemFactory.createItem("VEHICLE", "1", "Car", "desc", 100, "2020");
        Item electronics = ItemFactory.createItem("ELECTRONICS", "2", "Phone", "desc", 50, "BrandX");
        Item art = ItemFactory.createItem("ART", "3", "Painting", "desc", 200, "Artist");

        assertEquals("VEHICLE", vehicle.getType());
        assertEquals("ELECTRONICS", electronics.getType());
        assertEquals("ART", art.getType());
    }

    @Test
    void auctionPlaceBidValid() {
        Item item = ItemFactory.createItem("ELECTRONICS", "1", "Phone", "desc", 100, "BrandX");
        Auction auction = new Auction("a1", item, "owner1", "seller", System.currentTimeMillis(), System.currentTimeMillis() + 3600000);
        auction.setState(Auction.State.RUNNING);

        User bidder = new User("b1", "bidder", "password", User.Role.USER);
        assertTrue(auction.placeBid(bidder, 150));
        assertEquals(150, auction.getCurrentPrice());
        assertEquals("b1", auction.getHighestBidderId());
    }

    @Test
    void auctionCannotBidOnOwnAuction() {
        Item item = ItemFactory.createItem("ELECTRONICS", "1", "Phone", "desc", 100, "BrandX");
        Auction auction = new Auction("a1", item, "owner1", "seller", System.currentTimeMillis(), System.currentTimeMillis() + 3600000);
        auction.setState(Auction.State.RUNNING);

        User owner = new User("owner1", "seller", "password", User.Role.USER);
        assertFalse(auction.placeBid(owner, 150));
    }

    @Test
    void auctionCannotBidAgainstSelf() {
        Item item = ItemFactory.createItem("ELECTRONICS", "1", "Phone", "desc", 100, "BrandX");
        Auction auction = new Auction("a1", item, "owner1", "seller", System.currentTimeMillis(), System.currentTimeMillis() + 3600000);
        auction.setState(Auction.State.RUNNING);

        User bidder1 = new User("b1", "bidder1", "password", User.Role.USER);
        assertTrue(auction.placeBid(bidder1, 150));

        assertFalse(auction.placeBid(bidder1, 200));
    }

    @Test
    void auctionCannotBidWhenFinished() {
        Item item = ItemFactory.createItem("ELECTRONICS", "1", "Phone", "desc", 100, "BrandX");
        Auction auction = new Auction("a1", item, "owner1", "seller", System.currentTimeMillis(), System.currentTimeMillis() + 3600000);
        auction.setState(Auction.State.FINISHED);

        User bidder = new User("b1", "bidder", "password", User.Role.USER);
        assertFalse(auction.placeBid(bidder, 150));
    }

    @Test
    void auctionSnipingProtection() {
        Item item = ItemFactory.createItem("ELECTRONICS", "1", "Phone", "desc", 100, "BrandX");
        long now = System.currentTimeMillis();
        Auction auction = new Auction("a1", item, "owner1", "seller", now, now + 10000);
        auction.setState(Auction.State.RUNNING);

        User bidder = new User("b1", "bidder", "password", User.Role.USER);
        auction.placeBid(bidder, 150);

        assertTrue(auction.getEndTime() > now + 10000);
    }

    @Test
    void auctionHasBids() {
        Item item = ItemFactory.createItem("ELECTRONICS", "1", "Phone", "desc", 100, "BrandX");
        Auction auction = new Auction("a1", item, "owner1", "seller", System.currentTimeMillis(), System.currentTimeMillis() + 3600000);
        auction.setState(Auction.State.RUNNING);

        assertFalse(auction.hasBids());

        User bidder = new User("b1", "bidder", "password", User.Role.USER);
        auction.placeBid(bidder, 150);

        assertTrue(auction.hasBids());
    }

    @Test
    void auctionUpdateItemDoesNotResetPrice() {
        Item item = ItemFactory.createItem("ELECTRONICS", "1", "Phone", "desc", 100, "BrandX");
        Auction auction = new Auction("a1", item, "owner1", "seller", System.currentTimeMillis(), System.currentTimeMillis() + 3600000);
        auction.setState(Auction.State.RUNNING);

        User bidder = new User("b1", "bidder", "password", User.Role.USER);
        auction.placeBid(bidder, 150);
        assertEquals(150, auction.getCurrentPrice());

        Item newItem = ItemFactory.createItem("ELECTRONICS", "2", "NewPhone", "new desc", 50, "BrandY");
        auction.updateItem(newItem);

        assertEquals(150, auction.getCurrentPrice());
    }

    @Test
    void bidTransactionFields() {
        BidTransaction bid = new BidTransaction("b1", "bidder", 150.0, System.currentTimeMillis());
        assertEquals("b1", bid.getBidderId());
        assertEquals("bidder", bid.getBidderUsername());
        assertEquals(150.0, bid.getAmount());
    }

    @Test
    void userFromStoredHash() {
        String hash = "$2a$12$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ123";
        User user = User.fromStoredHash("1", "testuser", hash, User.Role.USER, 5, 1000L);
        assertEquals("1", user.getId());
        assertEquals("testuser", user.getUsername());
        assertEquals(User.Role.USER, user.getRole());
        assertEquals(5, user.getAuctionLimit());
        assertEquals(1000L, user.getCreatedAt());
    }
}
