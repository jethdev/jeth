/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.wallet;

import io.jeth.crypto.Wallet;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.ECPoint;

/**
 * BIP-32 / BIP-39 / BIP-44 Hierarchical Deterministic (HD) wallet.
 *
 * <p>Generates a 12-word mnemonic (BIP-39), derives a master key via PBKDF2-HMAC-SHA512, and then
 * uses BIP-32 child key derivation to produce an unlimited sequence of deterministic Ethereum
 * accounts.
 *
 * <p>The standard Ethereum derivation path is {@code m/44'/60'/0'/0/index}, matching MetaMask,
 * Ledger, Trezor, and all major wallets. Custom paths are also supported.
 *
 * <pre>
 * // Generate — 12 words from BIP-39 English wordlist
 * HdWallet hd = HdWallet.generate();
 * System.out.println(hd.getMnemonic()); // "word1 word2 ... word12"
 *
 * // Derive accounts (m/44'/60'/0'/0/index)
 * Wallet account0 = hd.getAccount(0);  // same as MetaMask account #1
 * Wallet account1 = hd.getAccount(1);  // MetaMask account #2
 *
 * // Restore from existing mnemonic — same addresses every time
 * HdWallet restored = HdWallet.fromMnemonic("word1 word2 ... word12");
 *
 * // Custom path (e.g. for a non-Ethereum coin or account index)
 * Wallet custom = hd.derive("m/44'/60'/1'/0/5");
 * </pre>
 *
 * <p><strong>BIP-39 wordlist note:</strong> run {@code ./gradlew fetchBip39Wordlist} once after
 * cloning to download the official 2048-word English list. Without it, {@link #generate()} may
 * produce mnemonics incompatible with MetaMask.
 *
 * @see io.jeth.wallet.Keystore
 * @see io.jeth.crypto.Wallet
 */
public class HdWallet {

    /** Standard Ethereum BIP-44 derivation path base. */
    public static final String ETH_PATH = "m/44'/60'/0'/0";

    private final byte[] seed;
    private final String mnemonic;
    private final ExtendedKey masterKey;

    private HdWallet(String mnemonic, byte[] seed) {
        this.mnemonic = mnemonic;
        this.seed = seed;
        this.masterKey = ExtendedKey.fromSeed(seed);
    }

    /** Generate a new 12-word BIP-39 mnemonic and HD wallet. */
    public static HdWallet generate() {
        return generate(128); // 128 bits = 12 words
    }

    /** Generate with custom entropy size (128=12 words, 192=18 words, 256=24 words). */
    public static HdWallet generate(int entropyBits) {
        byte[] entropy = new byte[entropyBits / 8];
        new SecureRandom().nextBytes(entropy);
        String mnemonic = entropyToMnemonic(entropy);
        byte[] seed = mnemonicToSeed(mnemonic, "");
        return new HdWallet(mnemonic, seed);
    }

    /** Restore from a 12/18/24 word BIP-39 mnemonic phrase. Validates words and checksum. */
    public static HdWallet fromMnemonic(String mnemonic) {
        return fromMnemonic(mnemonic, "");
    }

    /**
     * Restore with an optional BIP-39 passphrase (extra security). Validates words and checksum.
     */
    public static HdWallet fromMnemonic(String mnemonic, String passphrase) {
        validate(mnemonic); // throws MnemonicException on invalid input
        byte[] seed = mnemonicToSeed(mnemonic.trim(), passphrase);
        return new HdWallet(mnemonic.trim(), seed);
    }

    /**
     * Validate a BIP-39 mnemonic phrase without deriving any keys. Checks: word count
     * (12/15/18/21/24), all words in wordlist, checksum.
     *
     * @throws MnemonicException with a human-readable reason on any failure
     */
    public static void validate(String mnemonic) {
        if (mnemonic == null || mnemonic.isBlank())
            throw new MnemonicException("Mnemonic is null or empty");

        String[] words = mnemonic.trim().toLowerCase().split("\\s+");
        int n = words.length;
        if (n != 12 && n != 15 && n != 18 && n != 21 && n != 24)
            throw new MnemonicException(
                    "Invalid word count: " + n + " (must be 12, 15, 18, 21, or 24)");

        // Verify all words are in wordlist
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) {
            Integer idx = WORD_INDEX.get(words[i]);
            if (idx == null)
                throw new MnemonicException(
                        "Unknown word at position " + (i + 1) + ": '" + words[i] + "'");
            indices[i] = idx;
        }

        // Verify checksum: convert 11-bit indices back to bits, last CS bits must match SHA256
        int totalBits = n * 11;
        int entropyBits = totalBits * 32 / 33;
        int csLen = totalBits - entropyBits;

        boolean[] bits = new boolean[totalBits];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < 11; j++) bits[i * 11 + j] = (indices[i] & (1 << (10 - j))) != 0;

        byte[] entropy = new byte[entropyBits / 8];
        for (int i = 0; i < entropy.length; i++)
            for (int j = 0; j < 8; j++) if (bits[i * 8 + j]) entropy[i] |= 1 << (7 - j);

        SHA256Digest sha = new SHA256Digest();
        sha.update(entropy, 0, entropy.length);
        byte[] hash = new byte[32];
        sha.doFinal(hash, 0);

        for (int i = 0; i < csLen; i++) {
            boolean expected = (hash[i / 8] & (1 << (7 - i % 8))) != 0;
            if (bits[entropyBits + i] != expected)
                throw new MnemonicException("Invalid checksum — mnemonic may have a typo");
        }
    }

    /**
     * @return true if the mnemonic is valid, false otherwise (no exception).
     */
    public static boolean isValid(String mnemonic) {
        try {
            validate(mnemonic);
            return true;
        } catch (MnemonicException e) {
            return false;
        }
    }

    // Reverse index: word → BIP-39 index (lazy-built)
    private static final Map<String, Integer> WORD_INDEX;

    static {
        Map<String, Integer> idx = new HashMap<>(2048 * 2);
        for (int i = 0; i < WORDLIST.length; i++) idx.put(WORDLIST[i], i);
        WORD_INDEX = Collections.unmodifiableMap(idx);
    }

    /** Thrown when a mnemonic phrase is invalid. */
    public static class MnemonicException extends RuntimeException {
        public MnemonicException(String reason) {
            super("Invalid BIP-39 mnemonic: " + reason);
        }
    }

    /** Restore from raw 64-byte seed (no mnemonic). */
    public static HdWallet fromSeed(byte[] seed) {
        return new HdWallet(null, seed);
    }

    // ─── Account derivation ───────────────────────────────────────────────────

    /** Get account at standard Ethereum path: m/44'/60'/0'/0/{index} */
    public Wallet getAccount(int index) {
        return derive(ETH_PATH + "/" + index);
    }

    /** Get multiple accounts. */
    public List<Wallet> getAccounts(int count) {
        List<Wallet> wallets = new ArrayList<>();
        for (int i = 0; i < count; i++) wallets.add(getAccount(i));
        return wallets;
    }

    /** Derive a wallet at a custom BIP-44 path. e.g. "m/44'/60'/0'/0/5" or "m/44'/60'/1'/0/0" */
    public Wallet derive(String path) {
        ExtendedKey key = masterKey;
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part.equals("m")) continue;
            boolean hardened = part.endsWith("'");
            int index = Integer.parseInt(hardened ? part.substring(0, part.length() - 1) : part);
            if (hardened) index += 0x80000000;
            key = key.deriveChild(index);
        }
        return Wallet.fromPrivateKey(key.privateKey);
    }

    public String getMnemonic() {
        return mnemonic;
    }

    public byte[] getSeed() {
        return Arrays.copyOf(seed, seed.length);
    }

    // ─── BIP-39 mnemonic ──────────────────────────────────────────────────────

    private static final Logger BIP39_LOG = Logger.getLogger(HdWallet.class.getName());

    private static String[] loadWordlist() {
        // 1. Try classpath resource first (full official 2048-word list)
        try (var in = HdWallet.class.getResourceAsStream("/io/jeth/wallet/bip39-english.txt")) {
            if (in != null) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                String[] words = text.strip().split("\n");
                for (int i = 0; i < words.length; i++) words[i] = words[i].trim();
                if (words.length == 2048) return words;
                BIP39_LOG.warning(
                        "BIP-39 resource has "
                                + words.length
                                + " words (expected 2048). Falling back to embedded list.");
            }
        } catch (IOException ignored) {
        }

        // 2. Fallback: use embedded 2048-word list (no setup required)
        if (Bip39Words.WORDS.length != 2048) {
            BIP39_LOG.warning(
                    "Embedded BIP-39 wordlist has "
                            + Bip39Words.WORDS.length
                            + " words. "
                            + "Run: bash scripts/fetch-bip39-wordlist.sh for the official 2048-word list. "
                            + "HdWallet.fromMnemonic() is always correct; HdWallet.generate() may produce "
                            + "mnemonics with different word indices than other wallets.");
        }
        return Bip39Words.WORDS;
    }

    private static final String[] WORDLIST = loadWordlist();

    private static String entropyToMnemonic(byte[] entropy) {
        // Checksum: first (entropy_bits / 32) bits of SHA256(entropy)
        SHA256Digest sha = new SHA256Digest();
        sha.update(entropy, 0, entropy.length);
        byte[] hash = new byte[32];
        sha.doFinal(hash, 0);

        int checksumBits = entropy.length * 8 / 32;
        int totalBits = entropy.length * 8 + checksumBits;

        // Combine entropy + checksum into bit array
        boolean[] bits = new boolean[totalBits];
        for (int i = 0; i < entropy.length * 8; i++) {
            bits[i] = (entropy[i / 8] & (1 << (7 - i % 8))) != 0;
        }
        for (int i = 0; i < checksumBits; i++) {
            bits[entropy.length * 8 + i] = (hash[i / 8] & (1 << (7 - i % 8))) != 0;
        }

        // Group into 11-bit chunks → word indices
        int wordCount = totalBits / 11;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wordCount; i++) {
            int idx = 0;
            for (int j = 0; j < 11; j++) {
                idx = (idx << 1) | (bits[i * 11 + j] ? 1 : 0);
            }
            if (i > 0) sb.append(' ');
            sb.append(WORDLIST[idx]);
        }
        return sb.toString();
    }

    /** BIP-39: mnemonic + passphrase → 64-byte seed via PBKDF2-HMAC-SHA512. */
    private static byte[] mnemonicToSeed(String mnemonic, String passphrase) {
        try {
            String salt = "mnemonic" + passphrase;
            PBEKeySpec spec =
                    new PBEKeySpec(
                            mnemonic.toCharArray(),
                            salt.getBytes(StandardCharsets.UTF_8),
                            2048,
                            512);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive seed: " + e.getMessage(), e);
        }
    }

    // ─── BIP-32 extended key ──────────────────────────────────────────────────

    private static class ExtendedKey {
        final BigInteger privateKey;
        final byte[] chainCode;

        ExtendedKey(BigInteger privateKey, byte[] chainCode) {
            this.privateKey = privateKey;
            this.chainCode = chainCode;
        }

        static ExtendedKey fromSeed(byte[] seed) {
            HMac hmac = new HMac(new SHA512Digest());
            hmac.init(new KeyParameter("Bitcoin seed".getBytes(StandardCharsets.UTF_8)));
            hmac.update(seed, 0, seed.length);
            byte[] I = new byte[64];
            hmac.doFinal(I, 0);

            byte[] IL = Arrays.copyOfRange(I, 0, 32);
            byte[] IR = Arrays.copyOfRange(I, 32, 64);
            return new ExtendedKey(new BigInteger(1, IL), IR);
        }

        ExtendedKey deriveChild(int index) {
            HMac hmac = new HMac(new SHA512Digest());
            hmac.init(new KeyParameter(chainCode));

            byte[] data;
            if ((index & 0x80000000) != 0) {
                // Hardened: 0x00 || private_key || index
                data = new byte[37];
                data[0] = 0x00;
                byte[] privBytes = to32Bytes(privateKey);
                System.arraycopy(privBytes, 0, data, 1, 32);
            } else {
                // Normal: compressed_public_key || index
                ECPoint pub = Wallet.CURVE.getG().multiply(privateKey).normalize();
                byte[] pubCompressed = pub.getEncoded(true);
                data = new byte[37];
                System.arraycopy(pubCompressed, 0, data, 0, 33);
            }
            ByteBuffer.wrap(data, 33, 4).putInt(index);

            hmac.update(data, 0, data.length);
            byte[] I = new byte[64];
            hmac.doFinal(I, 0);

            byte[] IL = Arrays.copyOfRange(I, 0, 32);
            byte[] IR = Arrays.copyOfRange(I, 32, 64);

            BigInteger childKey = new BigInteger(1, IL).add(privateKey).mod(Wallet.CURVE.getN());

            return new ExtendedKey(childKey, IR);
        }

        private static byte[] to32Bytes(BigInteger value) {
            byte[] raw = value.toByteArray();
            byte[] out = new byte[32];
            if (raw.length <= 32) System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
            else System.arraycopy(raw, raw.length - 32, out, 0, 32);
            return out;
        }
    }
}
