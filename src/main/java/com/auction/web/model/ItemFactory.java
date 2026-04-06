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

        String normalizedName = name == null ? "" : name.trim();
        String normalizedDesc = description == null ? "" : description.trim();
        String normalizedExtra = extraInfo == null ? "" : extraInfo.trim();
        return switch (type) {
            case ELECTRONICS -> new Electronics(id, normalizedName, normalizedDesc, startingPrice, normalizedExtra);
            case ART -> new Art(id, normalizedName, normalizedDesc, startingPrice, normalizedExtra);
            case VEHICLE -> new Vehicle(id, normalizedName, normalizedDesc, startingPrice, parseYear(normalizedExtra));
            case BOOK -> new Book(id, normalizedName, normalizedDesc, startingPrice, normalizedExtra);
            case FASHION -> new Fashion(id, normalizedName, normalizedDesc, startingPrice, normalizedExtra);
            case COLLECTIBLE -> new Collectible(id, normalizedName, normalizedDesc, startingPrice, normalizedExtra);
            case PROPERTY -> new Property(id, normalizedName, normalizedDesc, startingPrice, normalizedExtra);
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
