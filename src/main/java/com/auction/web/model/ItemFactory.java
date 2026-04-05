package com.auction.web.model;

public class ItemFactory {
    public enum ItemType {
        ELECTRONICS, ART, VEHICLE, BOOK, FASHION, COLLECTIBLE, PROPERTY
    }

    public static Item createItem(String typeStr, String id, String name, String description, double startingPrice, String extraInfo) {
        ItemType type;
        try {
            type = ItemType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown item type: " + typeStr);
        }

        String normalizedExtra = extraInfo == null ? "" : extraInfo.trim();
        return switch (type) {
            case ELECTRONICS -> new Electronics(id, name.trim(), description.trim(), startingPrice, normalizedExtra);
            case ART -> new Art(id, name.trim(), description.trim(), startingPrice, normalizedExtra);
            case VEHICLE -> new Vehicle(id, name.trim(), description.trim(), startingPrice, parseYear(normalizedExtra));
            case BOOK -> new Book(id, name.trim(), description.trim(), startingPrice, normalizedExtra);
            case FASHION -> new Fashion(id, name.trim(), description.trim(), startingPrice, normalizedExtra);
            case COLLECTIBLE -> new Collectible(id, name.trim(), description.trim(), startingPrice, normalizedExtra);
            case PROPERTY -> new Property(id, name.trim(), description.trim(), startingPrice, normalizedExtra);
        };
    }

    private static int parseYear(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 2024;
        }
    }
}
