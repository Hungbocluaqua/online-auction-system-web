package com.auction.web.http;

import java.util.regex.Pattern;

public final class HtmlSanitizer {
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern EVENT_HANDLER_PATTERN = Pattern.compile("on\\w+\\s*=\\s*[\"'][^\"']*[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_HANDLER_PATTERN2 = Pattern.compile("on\\w+\\s*=\\s*[^\\s>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern JAVASCRIPT_URI_PATTERN = Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_URI_PATTERN = Pattern.compile("data\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");

    private HtmlSanitizer() {}

    public static String sanitize(String input) {
        if (input == null) return null;
        String sanitized = input;
        sanitized = SCRIPT_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = EVENT_HANDLER_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = EVENT_HANDLER_PATTERN2.matcher(sanitized).replaceAll("");
        sanitized = JAVASCRIPT_URI_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = DATA_URI_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = HTML_TAG_PATTERN.matcher(sanitized).replaceAll("");
        return sanitized.trim();
    }

    public static String sanitizeAuctionName(String input) {
        if (input == null) return null;
        String sanitized = input.replaceAll("[<>\"'&]", "");
        return sanitized.trim();
    }
}
