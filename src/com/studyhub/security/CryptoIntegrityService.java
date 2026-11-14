package com.studyhub.security;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * [MEMBER 3: Secure Network Programming]
 * Cryptographic service providing SHA-256 MessageDigest integrity hashing
 * and AES-128 payload encryption/decryption for confidential study communication.
 */
public class CryptoIntegrityService {

    private static final String HASH_ALGO = "SHA-256";
    private static final String CIPHER_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int AES_KEY_SIZE = 128;
    private static final int IV_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Computes the SHA-256 hexadecimal digest of a UTF-8 string.
     */
    public static String computeSha256(String input) {
        if (input == null) return "";
        return computeSha256(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Computes the SHA-256 hexadecimal digest of raw bytes.
     */
    public static String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH_ALGO);
            byte[] digest = md.digest(data);
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable in JVM", e);
        }
    }

    /**
     * Computes SHA-256 from an InputStream.
     */
    public static String computeSha256(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance(HASH_ALGO);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            md.update(buffer, 0, read);
        }
        return bytesToHex(md.digest());
    }

    /**
     * Generates a new 128-bit AES symmetric key encoded as Base64.
     */
    public static String generateAesKeyBase64() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_SIZE, SECURE_RANDOM);
            SecretKey secretKey = keyGen.generateKey();
            return Base64.getEncoder().encodeToString(secretKey.getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("AES key generator unavailable", e);
        }
    }

    /**
     * Encrypts plaintext using AES-128-CBC with a random 16-byte IV.
     * The resulting payload format is Base64(IV + Ciphertext).
     */
    public static String encryptAes(String plainText, String base64Key) throws Exception {
        if (plainText == null) return "";
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

        byte[] iv = new byte[IV_LENGTH];
        SECURE_RANDOM.nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

        byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + cipherBytes.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    /**
     * Decrypts Base64(IV + Ciphertext) using AES-128-CBC.
     */
    public static String decryptAes(String encryptedBase64, String base64Key) throws Exception {
        if (encryptedBase64 == null || encryptedBase64.isEmpty()) return "";
        byte[] combined = Base64.getDecoder().decode(encryptedBase64);
        if (combined.length < IV_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted payload length");
        }

        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

        int cipherLength = combined.length - IV_LENGTH;
        byte[] cipherBytes = new byte[cipherLength];
        System.arraycopy(combined, IV_LENGTH, cipherBytes, 0, cipherLength);

        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        byte[] plainBytes = cipher.doFinal(cipherBytes);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    /**
     * Converts a byte array into a lowercase hex string.
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
