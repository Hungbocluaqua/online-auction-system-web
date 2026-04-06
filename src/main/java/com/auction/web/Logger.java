package com.auction.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class Logger {
    public static void info(String message) { log("INFO", "app", message, null); }
    public static void warn(String message) { log("WARN", "app", message, null); }
    public static void error(String message) { log("ERROR", "app", message, null); }
    public static void error(String message, Throwable t) { log("ERROR", "app", message, t); }
    public static void info(String component, String message) { log("INFO", component, message, null); }
    public static void warn(String component, String message) { log("WARN", component, message, null); }
    public static void error(String component, String message) { log("ERROR", component, message, null); }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void log(String level, String component, String message, Throwable t) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("level", level);
        entry.put("component", component);
        entry.put("message", message);
        if (t != null) entry.put("error", t.getMessage());
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : entry.entrySet()) {
            if (!first) sb.append(", ");
            sb.append("\"").append(e.getKey()).append("\": ");
            Object val = e.getValue();
            if (val instanceof String) sb.append("\"").append(escapeJson((String) val)).append("\"");
            else sb.append(val);
            first = false;
        }
        sb.append("}");
        if ("ERROR".equals(level)) System.err.println(sb);
        else System.out.println(sb);
    }
}
