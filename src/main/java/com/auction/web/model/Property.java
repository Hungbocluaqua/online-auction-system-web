package com.auction.web.model;

public class Property extends Item {
    private final String location;

    public Property(String id, String name, String description, double startingPrice, String location) {
        super(id, name, description, startingPrice);
        this.location = location == null ? "" : location;
    }

    @Override
    public String getType() {
        return "PROPERTY";
    }

    @Override
    public String getExtraLabel() {
        return "Location";
    }

    @Override
    public String getExtraValue() {
        return location;
    }
}
