package com.auction.web.service;

import com.auction.web.Logger;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class ZaloPayService {
    private final int appId;
    private final String key1;
    private final String key2;
    private final String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    public ZaloPayService(int appId, String key1, String key2, boolean production) {
        this.appId = appId;
        this.key1 = key1;
        this.key2 = key2;
        this.baseUrl = production ? "https://openapi.zalopay.vn" : "https://sb-openapi.zalopay.vn";
    }

    public boolean isConfigured() {
        return appId > 0 && key1 != null && !key1.isBlank() && key2 != null && !key2.isBlank();
    }

    public Map<String, Object> createOrder(String appTransId, long amountVnd, String description, String buyerUsername, String redirectUrl) {
        if (!isConfigured()) {
            Map<String, Object> stub = new LinkedHashMap<>();
            stub.put("return_code", 1);
            stub.put("zp_trans_token", "stub_token_" + System.currentTimeMillis());
            stub.put("order_url", "https://sb-openapi.zalopay.vn/stub?trans_id=" + appTransId);
            stub.put("order_token", "stub_order_token");
            stub.put("qr_code", "");
            Logger.info("ZALOPAY STUB: createOrder amount=" + amountVnd + " transId=" + appTransId);
            return stub;
        }
        try {
            long appTime = System.currentTimeMillis();
            String embedData = "{\"preferred_payment_method\": [\"vietqr\"],\"redirecturl\":\"" + redirectUrl + "\"}";
            String item = "[]";
            String bankCode = "";
            String macData = appId + "|" + appTransId + "|" + appTime + "|" + buyerUsername + "|" + amountVnd + "|" + description + "|" + embedData + "|" + item + "|" + bankCode;
            String mac = hmacHex(key1, macData);

            String body = "app_id=" + appId
                + "&app_user=" + URLEncoder.encode(buyerUsername, StandardCharsets.UTF_8)
                + "&app_time=" + appTime
                + "&app_trans_id=" + appTransId
                + "&amount=" + amountVnd
                + "&description=" + URLEncoder.encode(description, StandardCharsets.UTF_8)
                + "&bank_code=" + bankCode
                + "&embed_data=" + URLEncoder.encode(embedData, StandardCharsets.UTF_8)
                + "&item=" + URLEncoder.encode(item, StandardCharsets.UTF_8)
                + "&mac=" + mac;

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v2/create"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return parseResponse(response.body());
            }
            Logger.warn("ZaloPay createOrder failed: " + response.statusCode() + " " + response.body());
            return Map.of("return_code", -1, "return_message", "Payment creation failed");
        } catch (IOException | InterruptedException e) {
            Logger.error("ZaloPay createOrder error", e);
            Thread.currentThread().interrupt();
            return Map.of("return_code", -1, "return_message", "Payment service unavailable");
        }
    }

    public boolean verifyCallback(String data, String mac) {
        if (!isConfigured()) return true;
        String expectedMac = hmacHex(key2, data);
        return expectedMac.equals(mac);
    }

    public Map<String, Object> queryOrder(String appTransId) {
        if (!isConfigured()) {
            Map<String, Object> stub = new LinkedHashMap<>();
            stub.put("return_code", 1);
            stub.put("is_processing", false);
            stub.put("amount", 0);
            stub.put("zp_trans_id", 0L);
            stub.put("status", 1);
            return stub;
        }
        try {
            long appTime = System.currentTimeMillis();
            String macData = appId + "|" + appTransId + "|" + key1;
            String mac = hmacHex(key1, macData);

            String body = "app_id=" + appId
                + "&app_trans_id=" + appTransId
                + "&mac=" + mac;

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v2/query"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return parseResponse(response.body());
            }
            Logger.warn("ZaloPay queryOrder failed: " + response.statusCode() + " " + response.body());
            return Map.of("return_code", -1, "return_message", "Query failed");
        } catch (IOException | InterruptedException e) {
            Logger.error("ZaloPay queryOrder error", e);
            Thread.currentThread().interrupt();
            return Map.of("return_code", -1, "return_message", "Payment service unavailable");
        }
    }

    private String hmacHex(String key, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private Map<String, Object> parseResponse(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = gson.fromJson(json, Map.class);
            result.put("return_code", parsed.get("return_code"));
            result.put("return_message", parsed.get("return_message"));
            result.put("zp_trans_token", parsed.get("zp_trans_token"));
            result.put("order_url", parsed.get("order_url"));
            result.put("order_token", parsed.get("order_token"));
            result.put("qr_code", parsed.get("qr_code"));
            result.put("zp_trans_id", parsed.get("zp_trans_id"));
            result.put("is_processing", parsed.get("is_processing"));
            result.put("status", parsed.get("status"));
            result.put("amount", parsed.get("amount"));
        } catch (Exception e) {
            result.put("return_code", -1);
            result.put("return_message", "Failed to parse response");
        }
        return result;
    }
}
