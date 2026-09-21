package com.example.defusion_bcp.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class CryptoDtos {
    private CryptoDtos() {
    }

    public record EncryptRequest(
        @NotBlank(message = "Pegue un JSON para encriptar")
        @Size(max = 1_000_000, message = "El JSON supera el tamaño permitido")
        String jsonText,
        Integer companyId
    ) {
    }

    public record EncryptResponse(
        int companyId,
        String data,
        String signature,
        JsonNode preview
    ) {
    }

    public record DecryptRequest(
        @NotBlank(message = "Pegue un valor para desencriptar")
        @Size(max = 2_000_000, message = "El contenido supera el tamaño permitido")
        String encryptedText,
        String signature
    ) {
    }

    public record DecryptResponse(
        String rawText,
        JsonNode json,
        Boolean signatureValid,
        String sourceField
    ) {
    }
}
