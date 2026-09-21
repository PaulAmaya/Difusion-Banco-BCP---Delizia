package com.example.defusion_bcp.controller;

import com.example.defusion_bcp.dto.CryptoDtos;
import com.example.defusion_bcp.service.BankCryptoService;
import com.example.defusion_bcp.service.CryptoOperationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "BCP_ENVIRONMENT", havingValue = "SANDBOX", matchIfMissing = true)
@RequestMapping("/api/crypto")
public class CryptoController {
    private static final List<String> ENCRYPTED_FIELDS = List.of("data", "body", "message", "encryptedText");

    private final BankCryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public CryptoController(BankCryptoService cryptoService, ObjectMapper objectMapper) {
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/encrypt")
    public CryptoDtos.EncryptResponse encrypt(@Valid @RequestBody CryptoDtos.EncryptRequest request) {
        JsonNode preview = parseJson(request.jsonText(), "El texto ingresado no es un JSON válido");
        int companyId = request.companyId() == null ? cryptoService.defaultCompanyId() : request.companyId();
        if (companyId <= 0) {
            throw new CryptoOperationException("companyId debe ser mayor que cero");
        }

        String data = cryptoService.encrypt(request.jsonText());
        return new CryptoDtos.EncryptResponse(
            companyId,
            data,
            cryptoService.sign(data),
            preview
        );
    }

    @PostMapping("/decrypt")
    public CryptoDtos.DecryptResponse decrypt(@Valid @RequestBody CryptoDtos.DecryptRequest request) {
        EncryptedInput input = resolveInput(request.encryptedText(), request.signature());
        String rawText = cryptoService.decrypt(input.encryptedText());
        JsonNode json = tryParseJson(rawText);
        Boolean signatureValid = input.signature() == null || input.signature().isBlank()
            ? null
            : cryptoService.verifySignature(input.encryptedText(), input.signature());
        return new CryptoDtos.DecryptResponse(rawText, json, signatureValid, input.sourceField());
    }

    private EncryptedInput resolveInput(String rawInput, String explicitSignature) {
        String trimmed = rawInput.trim();
        if (!trimmed.startsWith("{")) {
            return new EncryptedInput(trimmed, explicitSignature, "encryptedText");
        }

        JsonNode envelope = parseJson(trimmed, "El contenido ingresado no es un JSON válido");
        for (String field : ENCRYPTED_FIELDS) {
            JsonNode value = envelope.get(field);
            if (value != null && value.isString() && !value.stringValue().isBlank()) {
                JsonNode envelopeSignature = envelope.get("signature");
                String signature = explicitSignature;
                if ((signature == null || signature.isBlank())
                    && envelopeSignature != null && envelopeSignature.isString()) {
                    signature = envelopeSignature.stringValue();
                }
                return new EncryptedInput(value.stringValue(), signature, field);
            }
        }
        throw new CryptoOperationException(
            "El JSON debe contener un campo data, body, message o encryptedText"
        );
    }

    private JsonNode parseJson(String value, String message) {
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new CryptoOperationException(message, exception);
        }
    }

    private JsonNode tryParseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            return null;
        }
    }

    private record EncryptedInput(String encryptedText, String signature, String sourceField) {
    }
}
