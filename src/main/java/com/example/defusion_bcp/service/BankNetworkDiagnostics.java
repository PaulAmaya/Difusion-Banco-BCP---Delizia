package com.example.defusion_bcp.service;

import java.util.*;
import java.util.regex.Pattern;

final class BankNetworkDiagnostics {
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
        "(?i)(authorization|password|passphrase|username|token|signature|accountNumber|accountNo|documentNumber|FederalTaxID|data)[\"']?\\s*[=:]\\s*(?:\"[^\"]*\"|'[^']*'|[^,;\\s}]+)");

    private BankNetworkDiagnostics() {}

    static String describe(Throwable exception, String... secrets) {
        var output = new StringBuilder();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        int level = 0;
        for (Throwable cause = exception; cause != null && level < 12 && seen.add(cause); cause = cause.getCause()) {
            output.append("cause[").append(level++).append("]=").append(cause.getClass().getName())
                .append(": ").append(redact(cause.getMessage(), secrets)).append('\n');
            for (var frame : Arrays.stream(cause.getStackTrace()).limit(8).toList()) {
                output.append("  at ").append(frame).append('\n');
            }
        }
        return output.toString();
    }

    static String redact(String value, String... secrets) {
        String safe = value == null ? "(sin mensaje)" : value;
        for (String secret : Arrays.stream(secrets).filter(Objects::nonNull).filter(s -> !s.isBlank())
            .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            safe = safe.replace(secret, "[REDACTED]");
        }
        safe = SENSITIVE_FIELD.matcher(safe).replaceAll("$1=[REDACTED]");
        safe = safe.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[REDACTED_EMAIL]")
            .replaceAll("\\d{6,}", "[REDACTED_NUMBER]").replaceAll("[\\p{Cntrl}]", " ");
        return safe.length() <= 700 ? safe : safe.substring(0, 700) + " [truncated]";
    }
}
