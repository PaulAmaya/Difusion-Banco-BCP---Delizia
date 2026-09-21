package com.example.defusion_bcp.service;

import org.springframework.stereotype.Component;

@Component
public class SensitiveDataMasker {
    public String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= 4) {
            return "*".repeat(normalized.length());
        }
        return "*".repeat(normalized.length() - 4) + normalized.substring(normalized.length() - 4);
    }
}
