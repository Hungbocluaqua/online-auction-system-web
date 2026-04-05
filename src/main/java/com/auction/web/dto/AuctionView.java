package com.auction.web.dto;

import com.auction.web.model.Auction;
import com.auction.web.model.BidTransaction;
import java.util.List;

public class AuctionView {
    private String id;
    private String itemId;
    private String itemType;
    private String itemName;
    private String description;
    private String extraLabel;
    private String extraValue;
    private double startingPrice;
    private double currentPrice;
    private String ownerId;
    private String ownerUsername;
    private String highestBidderId;
    private String highestBidderUsername;
    private String imageFilename;
    private long startTime;
    private long endTime;
    private Auction.State state;
    private List<BidTransaction> bidHistory;
    private int bidCount;
    private int watchingCount;

    public AuctionView(Auction auction) {
        this.id = auction.getId();
        this.itemId = auction.getItem().getId();
        this.itemType = auction.getItem().getType();
        this.itemName = auction.getItem().getName();
        this.description = auction.getItem().getDescription();
        this.extraLabel = auction.getItem().getExtraLabel();
        this.extraValue = auction.getItem().getExtraValue();
        this.startingPrice = auction.getItem().getStartingPrice();
        this.currentPrice = auction.getCurrentPrice();
        this.ownerId = auction.getOwnerId();
        this.ownerUsername = auction.getOwnerUsername();
        this.highestBidderId = auction.getHighestBidderId();
        this.highestBidderUsername = auction.getHighestBidderUsername();
        this.imageFilename = auction.getImageFilename();
        this.startTime = auction.getStartTime();
        this.endTime = auction.getEndTime();
        this.state = auction.getState();
        this.bidHistory = auction.getBidHistory();
        this.bidCount = auction.getBidHistory().size();
        this.watchingCount = Math.max(0, (int) (Math.random() * 15) + (auction.getState() == Auction.State.RUNNING ? 2 : 0));
    }

    public String getId() { return id; }
    public String getItemId() { return itemId; }
    public String getItemType() { return itemType; }
    public String getItemName() { return itemName; }
    public String getDescription() { return description; }
    public String getExtraLabel() { return extraLabel; }
    public String getExtraValue() { return extraValue; }
    public double getStartingPrice() { return startingPrice; }
    public double getCurrentPrice() { return currentPrice; }
    public String getOwnerId() { return ownerId; }
    public String getOwnerUsername() { return ownerUsername; }
    public String getHighestBidderId() { return highestBidderId; }
    public String getHighestBidderUsername() { return highestBidderUsername; }
    public String getImageFilename() { return imageFilename; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public Auction.State getState() { return state; }
    public List<BidTransaction> getBidHistory() { return bidHistory; }
    public int getBidCount() { return bidCount; }
    public int getWatchingCount() { return watchingCount; }
}
