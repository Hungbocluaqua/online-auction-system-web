package com.auction.web.service;

import com.auction.web.Logger;
import com.auction.web.http.HttpUtil;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class EmailService {
    private final String apiKey;
    private final String fromEmail;
    private final String fromName;
    private final HttpClient client = HttpUtil.sharedClient();

    public EmailService(String apiKey, String fromEmail, String fromName) {
        this.apiKey = apiKey;
        this.fromEmail = fromEmail != null ? fromEmail : "noreply@auctionsystem.com";
        this.fromName = fromName != null ? fromName : "Auction System";
    }

    public boolean send(String to, String subject, String htmlBody) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("sk_test_")) {
            Logger.info("EMAIL STUB: To=" + to + " Subject=" + subject + " Body=" + htmlBody.substring(0, Math.min(100, htmlBody.length())));
            return true;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.sendgrid.com/v3/mail/send"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"personalizations\":[{\"to\":[{\"email\":\"" + escapeJson(to) + "\"}]}]," +
                    "\"from\":{\"email\":\"" + escapeJson(fromEmail) + "\",\"name\":\"" + escapeJson(fromName) + "\"}," +
                    "\"subject\":\"" + escapeJson(subject) + "\"," +
                    "\"content\":[{\"type\":\"text/html\",\"value\":\"" + escapeJson(htmlBody) + "\"}]}"
                ))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Logger.info("Email sent to " + to);
                return true;
            }
            Logger.warn("Email failed: " + response.statusCode() + " " + response.body());
            return false;
        } catch (IOException | InterruptedException e) {
            Logger.warn("Email send error: " + e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void sendPasswordReset(String to, String username, String resetLink) {
        send(to, "Reset Your Password",
            "<h2>Password Reset</h2><p>Hello " + escapeHtml(username) + ",</p>" +
            "<p>Click the link below to reset your password:</p>" +
            "<p><a href=\"" + escapeHtml(resetLink) + "\">Reset Password</a></p>" +
            "<p>This link expires in 1 hour.</p>");
    }

    public void sendEmailVerification(String to, String verifyLink) {
        send(to, "Verify Your Email",
            "<h2>Verify Your Email</h2>" +
            "<p>Click the link below to verify your email address:</p>" +
            "<p><a href=\"" + escapeHtml(verifyLink) + "\">Verify Email</a></p>");
    }

    public void sendPaymentConfirmation(String to, String username, String auctionName, double amount) {
        send(to, "Payment Confirmed - " + escapeHtml(auctionName),
            "<h2>Payment Confirmed</h2><p>Hello " + escapeHtml(username) + ",</p>" +
            "<p>Your payment of $" + String.format("%.2f", amount) + " for <strong>" + escapeHtml(auctionName) + "</strong> has been confirmed.</p>");
    }

    private String escapeJson(String s) {
        return HttpUtil.escapeJson(s);
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
