package com.auction.web.model;

public class Collectible extends Item {
    private final String series;

    public Collectible(String id, String name, String description, double startingPrice, String series) {
        super(id, name, description, startingPrice);
        this.series = series == null ? "" : series;
    }

    @Override
    public String getType() {
        return "COLLECTIBLE";
    }

    @Override
    public String getExtraLabel() {
        return "Series";
    }

    @Override
    public String getExtraValue() {
        return series;
    }
}
