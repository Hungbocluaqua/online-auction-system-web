package com.auction.web.model;

public abstract class Entity {
    private String id;

    protected Entity(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
