package com.auction.web.dto;

public class AuctionCreateRequest {
    public String itemType;
    public String name;
    public String description;
    public double startingPrice;
    public double buyItNowPrice;
    public double reservePrice;
    public String currency;
    public String extraInfo;
    public String imageFilename;
    public int durationMinutes;
    public long scheduledStartTime;
}
