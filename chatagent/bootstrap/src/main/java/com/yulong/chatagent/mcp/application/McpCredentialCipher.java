package com.yulong.chatagent.mcp.application;

import com.yulong.chatagent.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts persisted MCP credentials with application-managed AES-GCM.
 */
@Component
public class McpCredentialCipher {

    private static final String CIPHER_NAME = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final String base64Key;
    private final String keyVersion;
    private final SecureRandom secureRandom = new SecureRandom();

    public McpCredentialCipher(@Value("${chatagent.mcp.crypto.key:}") String base64Key,
                               @Value("${chatagent.mcp.crypto.key-version:v1}") String keyVersion) {
        this.base64Key = base64Key;
        this.keyVersion = keyVersion;
    }

    public EncryptedCredential encrypt(String plainText) {
        if (!StringUtils.hasText(plainText)) {
            return new EncryptedCredential(null, null);
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_NAME);
            cipher.init(Cipher.ENCRYPT_MODE, resolveKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return new EncryptedCredential(
                    Base64.getEncoder().encodeToString(buffer.array()),
                    keyVersion
            );
        } catch (GeneralSecurityException ex) {
            throw new BizException("Failed to encrypt MCP credentials");
        }
    }

    public String decrypt(String encryptedText, String credentialKeyVersion) {
        if (!StringUtils.hasText(encryptedText)) {
            return null;
        }
        if (!StringUtils.hasText(credentialKeyVersion) || !credentialKeyVersion.equals(keyVersion)) {
            throw new BizException("Unsupported MCP credential key version: " + credentialKeyVersion);
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encryptedText);
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] cipherBytes = new byte[buffer.remaining()];
            buffer.get(cipherBytes);

            Cipher cipher = Cipher.getInstance(CIPHER_NAME);
            cipher.init(Cipher.DECRYPT_MODE, resolveKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new BizException("Failed to decrypt MCP credentials");
        }
    }

    private SecretKey resolveKey() {
        if (!StringUtils.hasText(base64Key)) {
            throw new BizException("MCP credential cipher key is not configured");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Key);
            if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
                throw new BizException("MCP credential cipher key must be 128, 192, or 256 bits");
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException ex) {
            throw new BizException("MCP credential cipher key must be valid Base64");
        }
    }

    public record EncryptedCredential(String ciphertext, String keyVersion) {
    }
}
