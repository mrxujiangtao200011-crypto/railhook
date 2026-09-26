package com.webhook.platform.common.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Duration;
import java.util.Base64;
import lombok.Getter;

public final class SecretEncryption {

    /**
     * PBKDF2 is deliberately slow and was running on every encrypt and decrypt against one fixed
     * salt. Ingress decrypts a source's HMAC secret before the signature is checked, so that cost
     * was also triggerable by unauthenticated requests. Caching adds no exposure: the master key
     * is already in memory.
     */
    private static final Cache<DerivationKey, SecretKey> DERIVED_KEYS = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    /** Two fields, not one concatenated string, so distinct pairs cannot collide. */
    private record DerivationKey(String masterKey, String salt) {
    }

    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final int AES_KEY_LENGTH_BITS = 256;

    private SecretEncryption() {
    }

    public static EncryptedData encrypt(String plaintext, String masterKey, String salt) {
        return encrypt(plaintext, masterKey, salt, 1);
    }

    public static EncryptedData encrypt(String plaintext, String masterKey, String salt, int keyVersion) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);

            SecretKey key = deriveKey(masterKey, salt);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return new EncryptedData(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(iv),
                    keyVersion
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt secret", e);
        }
    }

    public static String decrypt(String ciphertext, String iv, String masterKey, String salt) {
        try {
            byte[] ciphertextBytes = Base64.getDecoder().decode(ciphertext);
            byte[] ivBytes = Base64.getDecoder().decode(iv);

            SecretKey key = deriveKey(masterKey, salt);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, ivBytes);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            byte[] plaintext = cipher.doFinal(ciphertextBytes);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt secret", e);
        }
    }

    private static SecretKey deriveKey(String masterKey, String salt) {
        return DERIVED_KEYS.get(new DerivationKey(masterKey, salt), SecretEncryption::derive);
    }

    private static SecretKey derive(DerivationKey key) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(key.masterKey().toCharArray(),
                    key.salt().getBytes(StandardCharsets.UTF_8), PBKDF2_ITERATIONS, AES_KEY_LENGTH_BITS);
            SecretKey tmp = factory.generateSecret(spec);
            return new SecretKeySpec(tmp.getEncoded(), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive encryption key", e);
        }
    }

    @Getter
    public static class EncryptedData {
        private final String ciphertext;
        private final String iv;
        private final int keyVersion;

        public EncryptedData(String ciphertext, String iv) {
            this(ciphertext, iv, 1);
        }

        public EncryptedData(String ciphertext, String iv, int keyVersion) {
            this.ciphertext = ciphertext;
            this.iv = iv;
            this.keyVersion = keyVersion;
        }
    }
}
