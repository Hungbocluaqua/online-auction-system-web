package com.auction.web.model;

public class Fashion extends Item {
    private final String brand;

    public Fashion(String id, String name, String description, double startingPrice, String brand) {
        super(id, name, description, startingPrice);
        this.brand = brand == null ? "" : brand;
    }

    @Override
    public String getType() {
        return "FASHION";
    }

    @Override
    public String getExtraLabel() {
        return "Brand";
    }

    @Override
    public String getExtraValue() {
        return brand;
    }
}
