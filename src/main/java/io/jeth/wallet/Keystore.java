/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jeth.crypto.Wallet;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import org.bouncycastle.crypto.generators.SCrypt;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.SICBlockCipher;
import org.bouncycastle.crypto.params.ParametersWithIV;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.UUID;

/**
 * Ethereum Keystore V3 — encrypted JSON wallet format.
 *
 * Compatible with MetaMask, Geth, MyCrypto, MyEtherWallet.
 * Uses scrypt (default) or pbkdf2 for key derivation.
 *
 * <pre>
 * // Encrypt a wallet
 * String json = Keystore.encrypt(wallet, "my-password");
 * Files.writeString(Path.of("UTC--2024-01-01T00-00-00Z--0xAddress.json"), json);
 *
 * // Decrypt a wallet
 * Wallet wallet = Keystore.decrypt(json, "my-password");
 * System.out.println(wallet.getAddress()); // 0xf39Fd6...
 *
 * // Load from file
 * String json = Files.readString(Path.of("keystore/UTC--...json"));
 * Wallet wallet = Keystore.decrypt(json, "password");
 *
 * // Light encryption (faster, less secure — use for testing only)
 * String json = Keystore.encryptLight(wallet, "password");
 * </pre>
 */
public final class Keystore {

    // Scrypt parameters (MetaMask/Geth defaults)
    public static final int SCRYPT_N_STANDARD = 1 << 18; // 262144 — production
    public static final int SCRYPT_N_LIGHT    = 1 << 12; // 4096   — testing
    public static final int SCRYPT_R          = 8;
    public static final int SCRYPT_P          = 1;
    public static final int SCRYPT_DKLEN      = 32;

    // AES-128-CTR parameters
    private static final int AES_IV_LEN  = 16;
    private static final int SALT_LEN    = 32;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RNG    = new SecureRandom();

    private Keystore() {}

    // ─── Encrypt ─────────────────────────────────────────────────────────────

    /**
     * Encrypt a wallet with standard scrypt parameters (secure, ~5s on modern hardware).
     * @return Keystore V3 JSON string
     */
    public static String encrypt(Wallet wallet, String password) {
        return encrypt(wallet, password, SCRYPT_N_STANDARD);
    }

    /**
     * Encrypt a wallet with lightweight scrypt (fast, for dev/testing only).
     * @return Keystore V3 JSON string
     */
    public static String encryptLight(Wallet wallet, String password) {
        return encrypt(wallet, password, SCRYPT_N_LIGHT);
    }

    /**
     * Encrypt a wallet with custom scrypt N parameter.
     * @param n scrypt N parameter (must be power of 2)
     */
    public static String encrypt(Wallet wallet, String password, int n) {
        byte[] salt = randomBytes(SALT_LEN);
        byte[] iv   = randomBytes(AES_IV_LEN);
        byte[] pwd  = password.getBytes(StandardCharsets.UTF_8);

        // Derive 32-byte key via scrypt
        byte[] derivedKey = SCrypt.generate(pwd, salt, n, SCRYPT_R, SCRYPT_P, SCRYPT_DKLEN);

        // Encrypt private key with AES-128-CTR using first 16 bytes of derived key
        byte[] encryptionKey = Arrays.copyOfRange(derivedKey, 0, 16);
        byte[] privateKeyBytes = Hex.decode(wallet.getPrivateKeyHex());
        byte[] ciphertext = aesCtr(encryptionKey, iv, privateKeyBytes);

        // MAC = keccak256(derivedKey[16..32] || ciphertext)
        byte[] macInput = new byte[16 + ciphertext.length];
        System.arraycopy(derivedKey, 16, macInput, 0, 16);
        System.arraycopy(ciphertext, 0, macInput, 16, ciphertext.length);
        byte[] mac = Keccak.hash(macInput);

        // Build JSON
        String id = UUID.randomUUID().toString();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 3);
        root.put("id", id);
        root.put("address", wallet.getAddress().toLowerCase().replace("0x", ""));

        ObjectNode crypto = root.putObject("crypto");
        crypto.put("cipher", "aes-128-ctr");
        crypto.putObject("cipherparams").put("iv", Hex.encodeNoPrefx(iv));
        crypto.put("ciphertext", Hex.encodeNoPrefx(ciphertext));

        ObjectNode kdfparams = crypto.putObject("kdfparams");
        kdfparams.put("dklen", SCRYPT_DKLEN);
        kdfparams.put("salt", Hex.encodeNoPrefx(salt));
        kdfparams.put("n", n);
        kdfparams.put("r", SCRYPT_R);
        kdfparams.put("p", SCRYPT_P);
        crypto.put("kdf", "scrypt");
        crypto.put("mac", Hex.encodeNoPrefx(mac));

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new KeystoreException("Failed to serialize keystore", e);
        }
    }

    // ─── Decrypt ─────────────────────────────────────────────────────────────

    /**
     * Decrypt a Keystore V3 JSON string and return the wallet.
     * @throws KeystoreException if password is wrong or JSON is invalid
     */
    public static Wallet decrypt(String json, String password) {
        try {
            JsonNode root   = MAPPER.readTree(json);
            int version     = root.path("version").asInt();
            if (version != 3) throw new KeystoreException("Unsupported keystore version: " + version);

            JsonNode crypto = root.path("crypto");
            if (crypto.isMissingNode()) crypto = root.path("Crypto"); // Geth compat

            String cipher   = crypto.path("cipher").asText();
            if (!"aes-128-ctr".equals(cipher))
                throw new KeystoreException("Unsupported cipher: " + cipher);

            byte[] ciphertext = Hex.decode(crypto.path("ciphertext").asText());
            byte[] iv         = Hex.decode(crypto.path("cipherparams").path("iv").asText());
            byte[] mac        = Hex.decode(crypto.path("mac").asText());
            byte[] pwd        = password.getBytes(StandardCharsets.UTF_8);

            String kdf = crypto.path("kdf").asText();
            JsonNode kdfparams = crypto.path("kdfparams");
            byte[] derivedKey;

            if ("scrypt".equals(kdf)) {
                byte[] salt = Hex.decode(kdfparams.path("salt").asText());
                int n       = kdfparams.path("n").asInt(SCRYPT_N_STANDARD);
                int r       = kdfparams.path("r").asInt(SCRYPT_R);
                int p       = kdfparams.path("p").asInt(SCRYPT_P);
                int dklen   = kdfparams.path("dklen").asInt(SCRYPT_DKLEN);
                derivedKey  = SCrypt.generate(pwd, salt, n, r, p, dklen);
            } else if ("pbkdf2".equals(kdf)) {
                derivedKey = pbkdf2(pwd, kdfparams);
            } else {
                throw new KeystoreException("Unsupported KDF: " + kdf);
            }

            // Verify MAC
            byte[] macInput = new byte[16 + ciphertext.length];
            System.arraycopy(derivedKey, 16, macInput, 0, 16);
            System.arraycopy(ciphertext, 0, macInput, 16, ciphertext.length);
            byte[] expectedMac = Keccak.hash(macInput);
            if (!java.security.MessageDigest.isEqual(mac, expectedMac))
                throw new KeystoreException("Wrong password or corrupted keystore (MAC mismatch)");

            // Decrypt
            byte[] encryptionKey   = Arrays.copyOfRange(derivedKey, 0, 16);
            byte[] privateKeyBytes = aesCtr(encryptionKey, iv, ciphertext);

            return Wallet.fromPrivateKey(privateKeyBytes);
        } catch (KeystoreException e) {
            throw e;
        } catch (Exception e) {
            throw new KeystoreException("Failed to decrypt keystore", e);
        }
    }

    // ─── Filename ─────────────────────────────────────────────────────────────

    /**
     * Generate the standard UTC keystore filename.
     * Example: UTC--2024-01-15T12-30-00.000000000Z--f39fd6e51aad88f6f4ce6ab8827279cfffb92266
     */
    public static String filename(Wallet wallet) {
        String ts  = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss.SSSSSSSSS'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        String addr = wallet.getAddress().toLowerCase().replace("0x", "");
        return "UTC--" + ts + "--" + addr;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** AES-128-CTR encrypt/decrypt (symmetric — CTR mode is self-inverse). */
    static byte[] aesCtr(byte[] key, byte[] iv, byte[] input) {
        SICBlockCipher ctr = new SICBlockCipher(new AESEngine());
        ctr.init(true, new ParametersWithIV(new KeyParameter(key), iv));
        byte[] output = new byte[input.length];
        ctr.processBytes(input, 0, input.length, output, 0);
        return output;
    }

    /** PBKDF2-SHA256 key derivation (legacy keystore compat). */
    private static byte[] pbkdf2(byte[] password, JsonNode params) throws Exception {
        byte[] salt  = Hex.decode(params.path("salt").asText());
        int    c     = params.path("c").asInt(262144);
        int    dklen = params.path("dklen").asInt(32);
        String prf   = params.path("prf").asText("hmac-sha256");
        if (!"hmac-sha256".equals(prf)) throw new KeystoreException("Unsupported PRF: " + prf);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(
                new String(password, StandardCharsets.UTF_8).toCharArray(), salt, c, dklen * 8);
        return factory.generateSecret(spec).getEncoded();
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    // ─── Exception ────────────────────────────────────────────────────────────

    public static class KeystoreException extends RuntimeException {
        public KeystoreException(String msg)                { super(msg); }
        public KeystoreException(String msg, Throwable cause) { super(msg, cause); }
    }
}
