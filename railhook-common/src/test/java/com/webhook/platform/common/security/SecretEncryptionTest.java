package com.webhook.platform.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SecretEncryption — encryption key versioning")
class SecretEncryptionTest {

    private static final String KEY = "test_master_key_32_chars_long_xx";
    private static final String SALT = "test_salt";

    @Test
    void encrypt_recordsTheKeyVersion_defaultingTo1() {
        SecretEncryption.EncryptedData data = SecretEncryption.encrypt("hello", KEY, SALT);

        assertEquals(1, data.getKeyVersion());
        assertFalse(data.getCiphertext().isBlank());
        assertFalse(data.getIv().isBlank());
        assertEquals(5, SecretEncryption.encrypt("hello", KEY, SALT, 5).getKeyVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"secret data", "", "Привіт 🔐 世界"})
    void encryptDecrypt_roundTrip(String plaintext) {
        SecretEncryption.EncryptedData data = SecretEncryption.encrypt(plaintext, KEY, SALT, 3);

        assertEquals(plaintext, SecretEncryption.decrypt(data.getCiphertext(), data.getIv(), KEY, SALT));
    }

    @Test
    void differentKeys_cannotDecryptEachOther() {
        String otherKey = "other_key_32_chars_long_pad_xxxx";

        SecretEncryption.EncryptedData data = SecretEncryption.encrypt("secret", KEY, SALT);

        assertThrows(RuntimeException.class, () ->
                SecretEncryption.decrypt(data.getCiphertext(), data.getIv(), otherKey, SALT));
    }

    @Test
    void differentSalts_cannotDecryptEachOther() {
        SecretEncryption.EncryptedData data = SecretEncryption.encrypt("secret", KEY, SALT);

        assertThrows(RuntimeException.class, () ->
                SecretEncryption.decrypt(data.getCiphertext(), data.getIv(), KEY, "different_salt"));
    }

    @Test
    void encrypt_sameData_producesDifferentCiphertext() {
        SecretEncryption.EncryptedData data1 = SecretEncryption.encrypt("same", KEY, SALT);
        SecretEncryption.EncryptedData data2 = SecretEncryption.encrypt("same", KEY, SALT);

        assertNotEquals(data1.getCiphertext(), data2.getCiphertext());
        assertNotEquals(data1.getIv(), data2.getIv());
    }

    // Re-deriving PBKDF2 on every decrypt let an unauthenticated /ingress caller burn CPU per request.
    @Test
    @DisplayName("a derived key is reused, not recomputed, for the same master key and salt")
    void derivationIsNotRepeated() {
        SecretEncryption.EncryptedData data = SecretEncryption.encrypt("payload", KEY, SALT);
        SecretEncryption.decrypt(data.getCiphertext(), data.getIv(), KEY, SALT);

        Instant start = Instant.now();
        for (int i = 0; i < 200; i++) {
            assertEquals("payload",
                    SecretEncryption.decrypt(data.getCiphertext(), data.getIv(), KEY, SALT));
        }
        Duration elapsed = Duration.between(start, Instant.now());

        assertTrue(elapsed.toMillis() < 2_000,
                "200 decrypts took " + elapsed.toMillis() + "ms — the key is being derived each time");
    }
}
