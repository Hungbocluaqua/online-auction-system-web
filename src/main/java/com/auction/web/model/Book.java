package com.auction.web.model;

public class Book extends Item {
    private final String author;

    public Book(String id, String name, String description, double startingPrice, String author) {
        super(id, name, description, startingPrice);
        this.author = author == null ? "" : author;
    }

    @Override
    public String getType() {
        return "BOOK";
    }

    @Override
    public String getExtraLabel() {
        return "Author";
    }

    @Override
    public String getExtraValue() {
        return author;
    }
}
