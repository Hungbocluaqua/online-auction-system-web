package com.auction.web.service;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class WebSocketServerImpl extends WebSocketServer {
    private final Set<WebSocket> conns = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<WebSocket> authenticated = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile Predicate<String> tokenValidator;

    public WebSocketServerImpl(int port) {
        super(new InetSocketAddress(port));
    }

    public void setTokenValidator(Predicate<String> validator) {
        this.tokenValidator = validator;
    }

    private boolean isValidToken(String token) {
        Predicate<String> v = tokenValidator;
        return v != null && v.test(token);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        conns.add(conn);
        String token = extractToken(handshake.getResourceDescriptor());
        if (token != null && isValidToken(token)) {
            authenticated.add(conn);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        conns.remove(conn);
        authenticated.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (!authenticated.contains(conn)) {
            if (message.startsWith("AUTH:")) {
                String token = message.substring(5);
                if (token != null && !token.isBlank() && isValidToken(token)) {
                    authenticated.add(conn);
                    return;
                }
            }
            conn.close(4001, "Authentication required");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("WebSocket error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("WebSocket server started on port: " + getPort());
    }

    public boolean tryStart() {
        try {
            start();
            return true;
        } catch (Exception e) {
            System.err.println("WARNING: WebSocket server failed to start on port " + getPort() + ": " + e.getMessage());
            return false;
        }
    }

    public void broadcastMessage(String message) {
        for (WebSocket conn : new java.util.ArrayList<>(authenticated)) {
            try {
                conn.send(message);
            } catch (Exception e) {
                authenticated.remove(conn);
            }
        }
    }

    public void stop() {
        try {
            for (WebSocket conn : conns) {
                conn.close(1001, "Server shutting down");
            }
        } catch (Exception ignored) {}
        try {
            super.stop();
        } catch (Exception ignored) {}
    }

    private static String extractToken(String resourceDescriptor) {
        if (resourceDescriptor == null || !resourceDescriptor.contains("?token=")) return null;
        int start = resourceDescriptor.indexOf("?token=") + 7;
        int end = resourceDescriptor.indexOf('&', start);
        return end == -1 ? resourceDescriptor.substring(start) : resourceDescriptor.substring(start, end);
    }
}
