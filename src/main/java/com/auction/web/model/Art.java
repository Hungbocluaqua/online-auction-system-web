package com.auction.web.model;

public class Art extends Item {
    private String artist;

    public Art(String id, String name, String description, double startingPrice, String artist) {
        super(id, name, description, startingPrice);
        this.artist = artist;
    }

    @Override
    public String getType() {
        return "Art";
    }

    @Override
    public String getExtraLabel() {
        return "Artist";
    }

    @Override
    public String getExtraValue() {
        return artist;
    }
}
