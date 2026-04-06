package com.auction.web;

import com.auction.web.config.AppConfig;
import com.auction.web.http.ApiHandler;
import com.auction.web.http.HealthHandler;
import com.auction.web.http.StaticFileHandler;
import com.auction.web.service.AuctionService;
import com.auction.web.service.EmailService;
import com.auction.web.service.ZaloPayService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class WebAuctionApplication {
    public static void main(String[] args) throws IOException {
        AppConfig config = AppConfig.load();
        String sendgridKey = System.getenv("SENDGRID_API_KEY");
        String fromEmail = System.getenv("EMAIL_FROM");
        String fromName = System.getenv("EMAIL_FROM_NAME");
        EmailService emailService = new EmailService(sendgridKey, fromEmail, fromName);

        String zaloAppId = System.getenv("ZALOPAY_APP_ID");
        String zaloKey1 = System.getenv("ZALOPAY_KEY1");
        String zaloKey2 = System.getenv("ZALOPAY_KEY2");
        boolean zaloProduction = "true".equals(System.getenv("ZALOPAY_PRODUCTION"));
        int appId = 0;
        try { appId = zaloAppId != null && !zaloAppId.isBlank() ? Integer.parseInt(zaloAppId) : 0; } catch (NumberFormatException ignored) {}
        ZaloPayService zaloPayService = new ZaloPayService(appId, zaloKey1, zaloKey2, zaloProduction);

        String pgUrl = config.getDatabaseUrl();
        String pgUser = config.getDatabaseUser();
        String pgPass = config.getDatabasePassword();

        boolean usePostgres = pgUrl != null && !pgUrl.isBlank() && !pgUrl.startsWith("jdbc:h2:");
        AuctionService service;
        if (usePostgres) {
            Logger.info("Using PostgreSQL database");
            service = new AuctionService(false, pgUrl, pgUser, pgPass, config);
        } else {
            Logger.warn("Using embedded H2 database - NOT suitable for production. Set DATABASE_URL or DB_URL to use PostgreSQL.");
            service = new AuctionService(false, null, null, null, config);
        }
        service.setEmailService(emailService);
        service.setZaloPayService(zaloPayService);
        service.setBaseUrl(config.getBaseUrl());
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        HealthHandler.setStartTime(System.currentTimeMillis());
        ApiHandler apiHandler = new ApiHandler(service, zaloPayService, gson, config);

        HttpServer server = HttpServer.create(new InetSocketAddress(config.getHttpPort()), 0);
        server.createContext("/api", apiHandler);
        server.createContext("/", new StaticFileHandler());
        server.createContext("/api/health", new HealthHandler(service.getDbManager()));
        server.createContext("/api/health/live", new HealthHandler(service.getDbManager()));
        server.createContext("/api/health/ready", new HealthHandler(service.getDbManager()));
        server.createContext("/api/metrics", new HealthHandler(service.getDbManager()));
        ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 20, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100));
        server.setExecutor(executor);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Logger.info("Shutting down...");
            service.shutdown();
            apiHandler.shutdown();
            server.stop(5);
            executor.shutdownNow();
        }));

        Logger.info("Web auction app running at " + config.getBaseUrl());
        if (sendgridKey == null || sendgridKey.isBlank()) {
            Logger.warn("Email service running in STUB mode - no real emails will be sent. Set SENDGRID_API_KEY to enable.");
        }
        if (!zaloPayService.isConfigured()) {
            Logger.warn("ZaloPay running in STUB mode - no real payments. Set ZALOPAY_APP_ID, ZALOPAY_KEY1, ZALOPAY_KEY2 to enable.");
        }
    }
}
