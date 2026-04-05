package com.auction.web.persistence;

import com.auction.web.model.AutoBidConfig;
import com.auction.web.model.Auction;
import com.auction.web.model.BidTransaction;
import com.auction.web.model.Item;
import com.auction.web.model.ItemFactory;
import com.auction.web.model.User;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class JsonDataStore implements SnapshotStore {
    private final Path filePath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public JsonDataStore(Path filePath) {
        this.filePath = filePath;
    }

    @Override
    public Snapshot load() throws IOException {
        if (!Files.exists(filePath)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(filePath)) {
            StoreFile storeFile = gson.fromJson(reader, StoreFile.class);
            if (storeFile == null) {
                return null;
            }
            List<User> users = new ArrayList<>();
            for (UserRecord record : storeFile.users) {
                users.add(User.fromStoredHash(record.id, record.username, record.passwordHash, record.role, record.auctionLimit, record.createdAt));
            }

            List<Auction> auctions = new ArrayList<>();
            for (AuctionRecord record : storeFile.auctions) {
                Item item = ItemFactory.createItem(
                    record.itemType,
                    record.itemId,
                    record.itemName,
                    record.description,
                    record.startingPrice,
                    record.extraValue
                );
                Auction auction = new Auction(
                    record.id,
                    item,
                    record.ownerId,
                    record.ownerUsername,
                    record.startTime,
                    record.endTime
                );
                List<AutoBidConfig> autoBids = new ArrayList<>();
                for (AutoBidRecord autoBid : record.autoBids) {
                    autoBids.add(new AutoBidConfig(
                        autoBid.bidderId,
                        autoBid.bidderUsername,
                        autoBid.maxBid,
                        autoBid.increment,
                        autoBid.registrationTime
                    ));
                }
                auction.restoreState(
                    record.imageFilename,
                    record.currency,                    record.currentPrice,
                    record.highestBidderId,
                    record.highestBidderUsername,
                    record.state,
                    new ArrayList<>(record.bidHistory),
                    autoBids
                );
                auctions.add(auction);
            }
            return new Snapshot(users, auctions);
        }
    }

    @Override
    public void save(Collection<User> users, Collection<Auction> auctions) throws IOException {
        Files.createDirectories(filePath.getParent());
        StoreFile storeFile = new StoreFile();
        storeFile.users = users.stream().map(user -> {
            UserRecord record = new UserRecord();
            record.id = user.getId();
            record.username = user.getUsername();
            record.passwordHash = user.getPasswordHashForStorage();
            record.role = user.getRole();
            record.auctionLimit = user.getAuctionLimit();
            record.createdAt = user.getCreatedAt();
            return record;
        }).toList();

        storeFile.auctions = auctions.stream().map(auction -> {
            AuctionRecord record = new AuctionRecord();
            record.id = auction.getId();
            record.itemId = auction.getItem().getId();
            record.itemType = auction.getItem().getType();
            record.itemName = auction.getItem().getName();
            record.description = auction.getItem().getDescription();
            record.extraValue = auction.getItem().getExtraValue();
            record.imageFilename = auction.getImageFilename();
            record.currency = auction.getCurrency();
            record.startingPrice = auction.getItem().getStartingPrice();
            record.ownerId = auction.getOwnerId();
            record.ownerUsername = auction.getOwnerUsername();
            record.startTime = auction.getStartTime();
            record.endTime = auction.getEndTime();
            record.currentPrice = auction.getCurrentPrice();
            record.highestBidderId = auction.getHighestBidderId();
            record.highestBidderUsername = auction.getHighestBidderUsername();
            record.state = auction.getState();
            record.bidHistory = new ArrayList<>(auction.getBidHistory());
            record.autoBids = auction.getAutoBids().stream().map(config -> {
                AutoBidRecord autoBid = new AutoBidRecord();
                autoBid.bidderId = config.getBidderId();
                autoBid.bidderUsername = config.getBidderUsername();
                autoBid.maxBid = config.getMaxBid();
                autoBid.increment = config.getIncrement();
                autoBid.registrationTime = config.getRegistrationTime();
                return autoBid;
            }).toList();
            return record;
        }).toList();

        try (Writer writer = Files.newBufferedWriter(filePath)) {
            gson.toJson(storeFile, writer);
        }
    }
    private static class StoreFile {
        private List<UserRecord> users = List.of();
        private List<AuctionRecord> auctions = List.of();
    }

    private static class UserRecord {
        private String id;
        private String username;
        private String passwordHash;
        private User.Role role;
        private Integer auctionLimit;
        private Long createdAt;
    }

    private static class AuctionRecord {
        private String id;
        private String itemId;
        private String itemType;
        private String itemName;
        private String description;
        private String extraValue;
        private String imageFilename;
        private String currency;
        private double startingPrice;
        private String ownerId;
        private String ownerUsername;
        private long startTime;
        private long endTime;
        private double currentPrice;
        private String highestBidderId;
        private String highestBidderUsername;
        private Auction.State state;
        private List<BidTransaction> bidHistory = List.of();
        private List<AutoBidRecord> autoBids = List.of();
    }

    private static class AutoBidRecord {
        private String bidderId;
        private String bidderUsername;
        private double maxBid;
        private double increment;
        private long registrationTime;
    }
}
