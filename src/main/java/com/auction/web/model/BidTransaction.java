package com.auction.web.model;

public class BidTransaction {
    private String bidderId;
    private String bidderUsername;
    private double amount;
    private long timestamp;

    public BidTransaction(String bidderId, String bidderUsername, double amount, long timestamp) {
        this.bidderId = bidderId;
        this.bidderUsername = bidderUsername;
        this.amount = amount;
        this.timestamp = timestamp;
    }

    public String getBidderId() {
        return bidderId;
    }

    public String getBidderUsername() {
        return bidderUsername;
    }

    public void setBidderUsername(String bidderUsername) {
        this.bidderUsername = bidderUsername;
    }

    public double getAmount() {
        return amount;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
