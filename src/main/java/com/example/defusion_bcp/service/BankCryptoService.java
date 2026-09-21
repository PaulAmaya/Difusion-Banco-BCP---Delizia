package com.example.defusion_bcp.service;

import com.example.defusion_bcp.config.BankCryptoProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;

@Service
public class BankCryptoService {
    private static final byte[] SALT = {1, 2, 3, 4, 5, 6, 7, 8};
    private static final int PBKDF2_ITERATIONS = 1000;
    private static final int KEY_AND_IV_LENGTH = 48;

    private final BankCryptoProperties properties;
    private volatile CryptoMaterial cryptoMaterial;

    @Autowired
    public BankCryptoService(BankCryptoProperties properties) {
        this.properties = properties;
    }

    BankCryptoService(BankCryptoProperties properties,
                      byte[] businessPublicKeyBytes,
                      PrivateKey signingPrivateKey,
                      PublicKey signingPublicKey) {
        this.properties = properties;
        this.cryptoMaterial = new CryptoMaterial(
            businessPublicKeyBytes.clone(), signingPrivateKey, signingPublicKey
        );
    }

    public int defaultCompanyId() {
        return properties.getCompanyId();
    }

    public String encrypt(String plainText) {
        try {
            byte[] encrypted = transformAes(
                plainText.getBytes(StandardCharsets.UTF_8),
                Cipher.ENCRYPT_MODE
            );
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new CryptoOperationException("No se pudo encriptar el JSON", exception);
        }
    }

    public String decrypt(String encryptedText) {
        try {
            String normalized = encryptedText.replaceAll("\\s", "");
            byte[] encrypted = Base64.getDecoder().decode(normalized);
            byte[] decrypted = transformAes(encrypted, Cipher.DECRYPT_MODE);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new CryptoOperationException("El texto encriptado no tiene un formato Base64 válido", exception);
        } catch (GeneralSecurityException exception) {
            throw new CryptoOperationException(
                "No se pudo desencriptar con el certificado BUSINESS configurado",
                exception
            );
        }
    }

    public String sign(String encryptedText) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(material().signingPrivateKey());
            signer.update(encryptedText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException exception) {
            throw new CryptoOperationException("No se pudo firmar con el certificado ENC_DESA", exception);
        }
    }

    public boolean verifySignature(String encryptedText, String signatureText) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(material().signingPublicKey());
            verifier.update(encryptedText.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureText.replaceAll("\\s", "")));
        } catch (IllegalArgumentException exception) {
            throw new CryptoOperationException("La firma no tiene un formato Base64 válido", exception);
        } catch (GeneralSecurityException exception) {
            throw new CryptoOperationException("No se pudo verificar la firma", exception);
        }
    }

    private byte[] transformAes(byte[] input, int mode) throws GeneralSecurityException {
        byte[] passwordBytes = MessageDigest.getInstance("SHA-256")
            .digest(material().businessPublicKeyBytes());
        byte[] derived = derivePbkdf2HmacSha1(passwordBytes, SALT, PBKDF2_ITERATIONS, KEY_AND_IV_LENGTH);
        byte[] key = Arrays.copyOfRange(derived, 0, 32);
        byte[] iv = Arrays.copyOfRange(derived, 32, 48);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(input);
    }

    static byte[] derivePbkdf2HmacSha1(byte[] password, byte[] salt, int iterations, int length)
        throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(password, "HmacSHA1"));
        int hashLength = mac.getMacLength();
        int blocks = (int) Math.ceil((double) length / hashLength);
        byte[] output = new byte[length];
        int outputOffset = 0;

        for (int block = 1; block <= blocks; block++) {
            byte[] blockInput = Arrays.copyOf(salt, salt.length + 4);
            blockInput[salt.length] = (byte) (block >>> 24);
            blockInput[salt.length + 1] = (byte) (block >>> 16);
            blockInput[salt.length + 2] = (byte) (block >>> 8);
            blockInput[salt.length + 3] = (byte) block;

            byte[] current = mac.doFinal(blockInput);
            byte[] combined = current.clone();
            for (int iteration = 1; iteration < iterations; iteration++) {
                current = mac.doFinal(current);
                for (int index = 0; index < combined.length; index++) {
                    combined[index] ^= current[index];
                }
            }

            int bytesToCopy = Math.min(hashLength, length - outputOffset);
            System.arraycopy(combined, 0, output, outputOffset, bytesToCopy);
            outputOffset += bytesToCopy;
        }
        return output;
    }

    private CryptoMaterial material() {
        CryptoMaterial current = cryptoMaterial;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cryptoMaterial == null) {
                cryptoMaterial = loadMaterial();
            }
            return cryptoMaterial;
        }
    }

    private CryptoMaterial loadMaterial() {
        ensureConfigured();
        char[] password = properties.getSigningCertificatePassword().toCharArray();
        try {
            Certificate businessCertificate;
            try (InputStream input = Files.newInputStream(Path.of(properties.getBusinessCertificatePath()))) {
                businessCertificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
            }

            KeyStore signingStore = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(Path.of(properties.getSigningCertificatePath()))) {
                signingStore.load(input, password);
            }

            Enumeration<String> aliases = signingStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Key key = signingStore.getKey(alias, password);
                Certificate certificate = signingStore.getCertificate(alias);
                if (key instanceof PrivateKey privateKey && certificate != null) {
                    return new CryptoMaterial(
                        extractSubjectPublicKeyBytes(businessCertificate.getPublicKey().getEncoded()),
                        privateKey,
                        certificate.getPublicKey()
                    );
                }
            }
            throw new CryptoOperationException("El certificado ENC_DESA no contiene una llave privada");
        } catch (IOException | GeneralSecurityException exception) {
            throw new CryptoOperationException("No se pudieron cargar los certificados BCP", exception);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void ensureConfigured() {
        if (properties.getBusinessCertificatePath().isBlank()
            || properties.getSigningCertificatePath().isBlank()
            || properties.getSigningCertificatePassword().isBlank()) {
            throw new CryptoOperationException("La configuración de certificados BCP está incompleta");
        }
    }

    static byte[] extractSubjectPublicKeyBytes(byte[] subjectPublicKeyInfo) {
        DerReader outer = new DerReader(subjectPublicKeyInfo);
        DerReader sequence = new DerReader(outer.readElement(0x30));
        sequence.readElement(0x30);
        byte[] bitString = sequence.readElement(0x03);
        if (bitString.length < 2 || bitString[0] != 0) {
            throw new CryptoOperationException("La llave pública BUSINESS no tiene un formato compatible");
        }
        return Arrays.copyOfRange(bitString, 1, bitString.length);
    }

    private record CryptoMaterial(
        byte[] businessPublicKeyBytes,
        PrivateKey signingPrivateKey,
        PublicKey signingPublicKey
    ) {
    }

    private static final class DerReader {
        private final ByteArrayInputStream input;

        private DerReader(byte[] bytes) {
            input = new ByteArrayInputStream(bytes);
        }

        private byte[] readElement(int expectedTag) {
            int tag = input.read();
            if (tag != expectedTag) {
                throw new CryptoOperationException("El certificado contiene una llave pública no válida");
            }
            int length = readLength();
            byte[] value = new byte[length];
            int bytesRead = input.read(value, 0, length);
            if (bytesRead != length) {
                throw new CryptoOperationException("El certificado está truncado");
            }
            return value;
        }

        private int readLength() {
            int first = input.read();
            if (first < 0) {
                throw new CryptoOperationException("El certificado está truncado");
            }
            if ((first & 0x80) == 0) {
                return first;
            }
            int byteCount = first & 0x7f;
            if (byteCount < 1 || byteCount > 4) {
                throw new CryptoOperationException("Longitud DER no válida en el certificado");
            }
            int length = 0;
            for (int index = 0; index < byteCount; index++) {
                int value = input.read();
                if (value < 0) {
                    throw new CryptoOperationException("El certificado está truncado");
                }
                length = (length << 8) | value;
            }
            return length;
        }
    }
}
