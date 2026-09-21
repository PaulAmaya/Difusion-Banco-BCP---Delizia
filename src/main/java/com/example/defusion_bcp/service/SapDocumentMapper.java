package com.example.defusion_bcp.service;

import com.example.defusion_bcp.dto.DiffusionDtos;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

public final class SapDocumentMapper {
    private SapDocumentMapper() {}

    public static String documentType(String value, DiffusionDtos.Catalogs catalogs) {
        return match(value, catalogs.documentTypes());
    }

    private static String match(String value, List<DiffusionDtos.CodeName> entries) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return "";
        return entries.stream().filter(entry -> normalize(entry.code()).equals(normalized)
            || normalize(entry.name()).equals(normalized)).map(DiffusionDtos.CodeName::code).findFirst().orElse("");
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT).replaceAll("[.]+", "")
            .trim().replaceAll("\\s+", " ");
    }
}
