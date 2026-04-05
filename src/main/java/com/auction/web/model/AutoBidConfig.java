package com.auction.web.model;

public class AutoBidConfig {
    private String bidderId;
    private String bidderUsername;
    private double maxBid;
    private double increment;
    private long registrationTime;

    public AutoBidConfig(String bidderId, String bidderUsername, double maxBid, double increment) {
        this.bidderId = bidderId;
        this.bidderUsername = bidderUsername;
        this.maxBid = maxBid;
        this.increment = increment;
        this.registrationTime = System.currentTimeMillis();
    }

    public AutoBidConfig(String bidderId, String bidderUsername, double maxBid, double increment, long registrationTime) {
        this.bidderId = bidderId;
        this.bidderUsername = bidderUsername;
        this.maxBid = maxBid;
        this.increment = increment;
        this.registrationTime = registrationTime;
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

    public double getMaxBid() {
        return maxBid;
    }

    public double getIncrement() {
        return increment;
    }

    public long getRegistrationTime() {
        return registrationTime;
    }
}
