package com.example.defusion_bcp.service;

import com.example.defusion_bcp.dto.DiffusionDtos;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class BankCodeResolver {
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
        Map.entry("1001", List.of("Banco Nacional de Bolivia", "BNB")),
        Map.entry("1005", List.of("Banco de Credito de Bolivia", "Banco de Credito", "BCP")),
        Map.entry("1007", List.of("Banco de la Nacion Argentina", "Banco Nacion Argentina")),
        Map.entry("1009", List.of("Banco BISA", "BISA")),
        Map.entry("1017", List.of("Banco Solidario", "BancoSol", "Banco Sol")),
        Map.entry("1033", List.of("Banco FIE", "FIE")),
        Map.entry("74002", List.of("Banco Ecofuturo", "Banco PYME Ecofuturo", "Ecofuturo")),
        Map.entry("74003", List.of("Banco de la Comunidad", "Banco PYME de la Comunidad"))
    );
    private final List<DiffusionDtos.CodeName> banks;
    private final DiffusionDtos.Catalogs catalogs;

    public BankCodeResolver(ObjectMapper mapper) {
        try (var input = new ClassPathResource("bank/payment-catalogs.json").getInputStream()) {
            catalogs = mapper.readValue(input, DiffusionDtos.Catalogs.class);
            banks = catalogs.banks();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo cargar el catalogo de bancos", exception);
        }
    }

    public DiffusionDtos.Catalogs catalogs() { return catalogs; }

    public DiffusionDtos.CodeName resolve(String sapCode, String bankName) {
        return resolve(sapCode, bankName, banks);
    }

    public static DiffusionDtos.CodeName resolve(String sapCode, String bankName, List<DiffusionDtos.CodeName> banks) {
        var direct = banks.stream().filter(bank -> bank.code().equals(sapCode == null ? "" : sapCode.trim())).findFirst();
        if (direct.isPresent()) return direct.get();
        String name = normalize(bankName);
        if (name.isEmpty()) return null;
        var matches = banks.stream().filter(bank -> normalize(bank.name()).equals(name)
            || ALIASES.getOrDefault(bank.code(), List.of()).stream().anyMatch(alias -> normalize(alias).equals(name))).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", " ").trim()
            .replaceFirst("(?: S A M| S A| S R L| LTDA)$", "").trim();
    }
}
