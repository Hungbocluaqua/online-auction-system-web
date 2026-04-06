package com.auction.web.model;

public class Vehicle extends Item {
    private int year;

    public Vehicle(String id, String name, String description, double startingPrice, int year) {
        super(id, name, description, startingPrice);
        this.year = year;
    }

    @Override
    public String getType() {
        return "VEHICLE";
    }

    @Override
    public String getExtraLabel() {
        return "Year";
    }

    @Override
    public String getExtraValue() {
        return String.valueOf(year);
    }
}
