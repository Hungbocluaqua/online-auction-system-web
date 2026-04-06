package com.auction.web.model;

public class Electronics extends Item {
    private String brand;

    public Electronics(String id, String name, String description, double startingPrice, String brand) {
        super(id, name, description, startingPrice);
        this.brand = brand;
    }

    @Override
    public String getType() {
        return "ELECTRONICS";
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
