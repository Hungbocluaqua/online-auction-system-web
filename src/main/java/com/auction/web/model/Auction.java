package com.auction.web.model;

import java.util.ArrayList;
import java.util.List;

public class Auction extends Entity {
    public enum State {
        OPEN,
        RUNNING,
        FINISHED,
        CANCELED
    }

    private Item item;
    private String ownerId;
    private String ownerUsername;
    private long startTime;
    private long endTime;
    private double currentPrice;
    private String highestBidderId;
    private String highestBidderUsername;
    private String imageFilename;
    private String currency;
    private double buyItNowPrice;
    private double reservePrice;
    private State state;
    private List<BidTransaction> bidHistory;
    private List<AutoBidConfig> autoBids;

    public Auction(String id, Item item, String ownerId, String ownerUsername, long startTime, long endTime) {
        super(id);
        this.item = item;
        this.ownerId = ownerId;
        this.ownerUsername = ownerUsername;
        this.startTime = startTime;
        this.endTime = endTime;
        this.currentPrice = item.getStartingPrice();
        this.imageFilename = "";
        this.currency = "USD";
        this.buyItNowPrice = 0;
        this.reservePrice = 0;
        this.state = State.OPEN;
        this.bidHistory = new ArrayList<>();
        this.autoBids = new ArrayList<>();
    }

    public Item getItem() {
        return item;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = ownerUsername;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void reschedule(long startTime, long endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public double getCurrentPrice() {
        return currentPrice;
    }

    public String getHighestBidderId() {
        return highestBidderId;
    }

    public String getHighestBidderUsername() {
        return highestBidderUsername;
    }

    public State getState() {
        return state;
    }

    public String getImageFilename() {
        return imageFilename;
    }

    public String getCurrency() {
        return currency;
    }

    public List<BidTransaction> getBidHistory() {
        return bidHistory;
    }

    public List<AutoBidConfig> getAutoBids() {
        return autoBids;
    }

    public synchronized void registerAutoBid(AutoBidConfig config) {
        autoBids.removeIf(existing -> existing.getBidderId().equals(config.getBidderId()));
        autoBids.add(config);
        evaluateAutoBids();
    }

    public synchronized boolean placeBid(User bidder, double amount) {
        return placeBidInternal(bidder, amount, true);
    }

    private synchronized boolean placeBidInternal(User bidder, double amount, boolean triggerAutoBids) {
        if (state == State.OPEN && System.currentTimeMillis() >= startTime) {
            state = State.RUNNING;
        }
        if (state != State.RUNNING) {
            return false;
        }
        if (System.currentTimeMillis() > endTime) {
            state = State.FINISHED;
            return false;
        }
        if (bidder.getId().equals(ownerId)) {
            return false;
        }
        if (bidder.getId().equals(highestBidderId)) {
            return false;
        }
        if (amount <= currentPrice) {
            return false;
        }

        currentPrice = amount;
        highestBidderId = bidder.getId();
        highestBidderUsername = bidder.getUsername();
        bidHistory.add(new BidTransaction(bidder.getId(), bidder.getUsername(), amount, System.currentTimeMillis()));

        if (endTime - System.currentTimeMillis() < 30_000) {
            endTime += 30_000;
        }

        if (triggerAutoBids) {
            evaluateAutoBids();
        }
        return true;
    }

    private void evaluateAutoBids() {
        // This is handled by AuctionService now to maintain DB sync
    }

    public void setState(State state) {
        this.state = state;
    }

    public void setImageFilename(String imageFilename) {
        this.imageFilename = imageFilename == null ? "" : imageFilename.trim();
    }

    public void setCurrency(String currency) {
        this.currency = currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase();
    }

    public double getBuyItNowPrice() { return buyItNowPrice; }
    public void setBuyItNowPrice(double buyItNowPrice) { this.buyItNowPrice = buyItNowPrice; }
    public double getReservePrice() { return reservePrice; }
    public void setReservePrice(double reservePrice) { this.reservePrice = reservePrice; }
    public boolean hasBuyItNow() { return buyItNowPrice > 0; }
    public boolean hasReserve() { return reservePrice > 0; }
    public boolean reserveMet() { return !hasReserve() || currentPrice >= reservePrice; }

    public void setCurrentPrice(double currentPrice) {
        this.currentPrice = currentPrice;
    }

    public void setHighestBidder(String highestBidderId, String highestBidderUsername) {
        this.highestBidderId = highestBidderId;
        this.highestBidderUsername = highestBidderUsername;
    }

    public void restoreState(
        String imageFilename,
        String currency,
        double currentPrice,
        String highestBidderId,
        String highestBidderUsername,
        State state,
        List<BidTransaction> bidHistory,
        List<AutoBidConfig> autoBids
    ) {
        this.imageFilename = imageFilename == null ? "" : imageFilename;
        this.currency = currency == null || currency.isBlank() ? "USD" : currency;
        this.currentPrice = currentPrice;
        this.highestBidderId = highestBidderId;
        this.highestBidderUsername = highestBidderUsername;
        this.state = state;
        this.bidHistory = new ArrayList<>(bidHistory);
        this.autoBids = new ArrayList<>(autoBids);
    }

    public boolean hasBids() {
        return !bidHistory.isEmpty();
    }

    public void updateItem(Item item) {
        this.item = item;
    }

    public void syncUsername(String userId, String username) {
        if (ownerId != null && ownerId.equals(userId)) {
            ownerUsername = username;
        }
        if (highestBidderId != null && userId.equals(highestBidderId)) {
            highestBidderUsername = username;
        }
        for (BidTransaction bid : bidHistory) {
            if (userId.equals(bid.getBidderId())) {
                bid.setBidderUsername(username);
            }
        }
        for (AutoBidConfig autoBid : autoBids) {
            if (userId.equals(autoBid.getBidderId())) {
                autoBid.setBidderUsername(username);
            }
        }
    }

    public boolean involvesUser(String userId) {
        if (userId == null) return false;
        if ((ownerId != null && ownerId.equals(userId)) || (highestBidderId != null && highestBidderId.equals(userId))) {
            return true;
        }
        return bidHistory.stream().anyMatch(bid -> userId.equals(bid.getBidderId()))
            || autoBids.stream().anyMatch(config -> userId.equals(config.getBidderId()));
    }
}
