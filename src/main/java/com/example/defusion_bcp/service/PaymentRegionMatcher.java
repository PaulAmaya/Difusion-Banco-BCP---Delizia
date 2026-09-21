package com.example.defusion_bcp.service;

import com.example.defusion_bcp.dto.DiffusionDtos;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

public final class PaymentRegionMatcher {
    private PaymentRegionMatcher() {}

    public static boolean matchesCity(String city, DiffusionDtos.Region region) {
        String value = normalize(city);
        return value.equals(region.code()) || value.equals(normalize(region.name()))
            || value.equals(Integer.toString(region.cityCode()));
    }

    public static DiffusionDtos.Region regionForCity(String city, List<DiffusionDtos.Region> regions) {
        return regions.stream().filter(region -> matchesCity(city, region)).findFirst().orElse(null);
    }

    public static DiffusionDtos.Region regionForSapCity(String value, DiffusionDtos.Catalogs catalogs) {
        String city = value == null ? "" : value.trim();
        var entry = catalogs.sapCities().stream().filter(item -> item.value().equals(city)).findFirst().orElse(null);
        return entry == null ? null : catalogs.regions().stream()
            .filter(region -> region.code().equals(entry.region())).findFirst().orElse(null);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
