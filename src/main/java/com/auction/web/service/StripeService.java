package com.auction.web.service;

import com.auction.web.Logger;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.LinkedHashMap;

public class StripeService {
    private final String secretKey;
    private final String webhookSecret;
    private final HttpClient client = HttpClient.newHttpClient();

    public StripeService(String secretKey, String webhookSecret) {
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
    }

    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank() && !secretKey.startsWith("sk_test_");
    }

    public Map<String, Object> createPaymentIntent(String idempotencyKey, long amountCents, String currency, String description) {
        if (!isConfigured()) {
            Map<String, Object> stub = new LinkedHashMap<>();
            stub.put("id", "pi_stub_" + System.currentTimeMillis());
            stub.put("client_secret", "pi_stub_secret");
            stub.put("status", "requires_payment_method");
            Logger.info("STRIPE STUB: createPaymentIntent amount=" + amountCents + " currency=" + currency);
            return stub;
        }
        try {
            String body = "amount=" + amountCents + "&currency=" + currency + "&description=" + java.net.URLEncoder.encode(description, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.stripe.com/v1/payment_intents"))
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return parseStripeResponse(response.body());
            }
            Logger.warn("Stripe createPaymentIntent failed: " + response.statusCode() + " " + response.body());
            return Map.of("error", "Payment creation failed");
        } catch (IOException | InterruptedException e) {
            Logger.warn("Stripe error: " + e.getMessage());
            Thread.currentThread().interrupt();
            return Map.of("error", "Payment service unavailable");
        }
    }

    public Map<String, Object> confirmPaymentIntent(String paymentIntentId) {
        if (!isConfigured()) {
            Map<String, Object> stub = new LinkedHashMap<>();
            stub.put("id", paymentIntentId);
            stub.put("status", "succeeded");
            return stub;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.stripe.com/v1/payment_intents/" + paymentIntentId + "/confirm"))
                .header("Authorization", "Bearer " + secretKey)
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return parseStripeResponse(response.body());
            }
            return Map.of("error", "Confirmation failed");
        } catch (IOException | InterruptedException e) {
            Logger.warn("Stripe confirm error: " + e.getMessage());
            Thread.currentThread().interrupt();
            return Map.of("error", "Payment service unavailable");
        }
    }

    public boolean verifyWebhookSignature(String payload, String sigHeader) {
        if (!isConfigured()) return true;
        if (webhookSecret == null || webhookSecret.isBlank()) return false;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String expectedSig = java.util.Base64.getEncoder().encodeToString(hash);
            return sigHeader.contains(expectedSig);
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> parseStripeResponse(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = gson.fromJson(json, Map.class);
            result.put("id", parsed.get("id"));
            result.put("client_secret", parsed.get("client_secret"));
            result.put("status", parsed.get("status"));
        } catch (Exception e) {
            result.put("error", "Failed to parse response");
        }
        return result;
    }
}
