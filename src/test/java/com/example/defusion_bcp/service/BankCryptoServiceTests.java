package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankCryptoProperties;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BankCryptoServiceTests {
    private static final String JSON = "{\"companyId\":2295,\"period\":\"202212\"}";
    private static final String NODE_VECTOR =
        "le3uddl41iaLwNadjFkmTp6lxLw838XpGEE0lZZebs4dYBVzPHt6F/22lmFmJgSB";

    @Test
    void encryptionMatchesIndependentNodePbkdf2AndAesVector() throws Exception {
        KeyPair signingKeys = rsaKeyPair();
        byte[] publicKeyBytes = new byte[16];
        IntStream.range(0, publicKeyBytes.length).forEach(index -> publicKeyBytes[index] = (byte) index);
        BankCryptoService service = service(publicKeyBytes, signingKeys);

        String encrypted = service.encrypt(JSON);

        assertThat(encrypted).isEqualTo(NODE_VECTOR);
        assertThat(service.decrypt(encrypted)).isEqualTo(JSON);
    }

    @Test
    void signatureUsesRsaSha256AndDetectsChanges() throws Exception {
        KeyPair signingKeys = rsaKeyPair();
        BankCryptoService service = service(new byte[] {1, 2, 3}, signingKeys);
        String encrypted = service.encrypt(JSON);

        String signature = service.sign(encrypted);

        assertThat(service.verifySignature(encrypted, signature)).isTrue();
        assertThat(service.verifySignature(encrypted + "A", signature)).isFalse();
    }

    @Test
    void configuredCertificatesCanEncryptDecryptSignAndVerify() {
        String businessCertificate = System.getenv("TEST_BCP_BUSINESS_CERTIFICATE_PATH");
        String signingCertificate = System.getenv("TEST_BCP_SIGNING_CERTIFICATE_PATH");
        String signingPassword = System.getenv("TEST_BCP_SIGNING_CERTIFICATE_PASSWORD");
        assumeTrue(isConfigured(businessCertificate, signingCertificate, signingPassword));

        BankCryptoProperties properties = new BankCryptoProperties();
        properties.setBusinessCertificatePath(businessCertificate);
        properties.setSigningCertificatePath(signingCertificate);
        properties.setSigningCertificatePassword(signingPassword);
        BankCryptoService service = new BankCryptoService(properties);

        String encrypted = service.encrypt(JSON);
        String signature = service.sign(encrypted);

        assertThat(service.decrypt(encrypted)).isEqualTo(JSON);
        assertThat(service.verifySignature(encrypted, signature)).isTrue();
    }

    private BankCryptoService service(byte[] publicKeyBytes, KeyPair signingKeys) {
        BankCryptoProperties properties = new BankCryptoProperties();
        return new BankCryptoService(
            properties,
            publicKeyBytes,
            signingKeys.getPrivate(),
            signingKeys.getPublic()
        );
    }

    private KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private boolean isConfigured(String... values) {
        return java.util.Arrays.stream(values).allMatch(value -> value != null && !value.isBlank());
    }
}
