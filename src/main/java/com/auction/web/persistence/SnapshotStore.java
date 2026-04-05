package com.auction.web.persistence;

import com.auction.web.model.Auction;
import com.auction.web.model.User;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

public interface SnapshotStore {
    Snapshot load() throws IOException;

    void save(Collection<User> users, Collection<Auction> auctions) throws IOException;

    record Snapshot(List<User> users, List<Auction> auctions) {
    }
}
