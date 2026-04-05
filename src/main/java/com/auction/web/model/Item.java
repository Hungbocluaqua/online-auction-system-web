package com.auction.web.model;

public abstract class Item extends Entity {
    private String name;
    private String description;
    private double startingPrice;

    protected Item(String id, String name, String description, double startingPrice) {
        super(id);
        this.name = name;
        this.description = description;
        this.startingPrice = startingPrice;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public double getStartingPrice() {
        return startingPrice;
    }

    public abstract String getType();

    public abstract String getExtraLabel();

    public abstract String getExtraValue();
}
