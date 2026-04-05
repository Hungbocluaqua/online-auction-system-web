package com.auction.web;

import com.auction.web.http.ApiHandler;
import com.auction.web.http.StaticFileHandler;
import com.auction.web.persistence.DatabaseSnapshotStore;
import com.auction.web.persistence.JsonDataStore;
import com.auction.web.persistence.SnapshotStore;
import com.auction.web.service.AuctionService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class WebAuctionApplication {
    public static void main(String[] args) throws IOException {
        AuctionService service = new AuctionService(createSnapshotStore());
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api", new ApiHandler(service, gson));
        server.createContext("/", new StaticFileHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("Web auction app running at http://localhost:8080");
    }

    private static SnapshotStore createSnapshotStore() {
        String storageMode = System.getenv().getOrDefault("AUCTION_STORAGE", "db").trim().toLowerCase();
        if ("json".equals(storageMode)) {
            return new JsonDataStore(java.nio.file.Path.of("data", "auction-store.json"));
        }
        return DatabaseSnapshotStore.forFile(java.nio.file.Path.of("data", "auction-db"));
    }
}
