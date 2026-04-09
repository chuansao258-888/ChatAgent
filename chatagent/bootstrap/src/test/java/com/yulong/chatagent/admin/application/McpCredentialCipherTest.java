package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.exception.BizException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpCredentialCipherTest {

    private static final String BASE64_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void shouldEncryptAndDecrypt() {
        McpCredentialCipher cipher = new McpCredentialCipher(BASE64_KEY, "v1");

        McpCredentialCipher.EncryptedCredential encrypted = cipher.encrypt("secret-token");

        assertThat(encrypted.ciphertext()).isNotBlank();
        assertThat(cipher.decrypt(encrypted.ciphertext(), encrypted.keyVersion())).isEqualTo("secret-token");
    }

    @Test
    void shouldRejectMissingKey() {
        McpCredentialCipher cipher = new McpCredentialCipher("", "v1");

        assertThatThrownBy(() -> cipher.encrypt("secret-token"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("cipher key");
    }
}
