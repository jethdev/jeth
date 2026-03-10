/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.crypto;

import java.util.concurrent.CompletableFuture;
import io.jeth.core.EthException;
import io.jeth.core.EthClient;
import io.jeth.model.EthModels;
import io.jeth.util.Address;
import io.jeth.util.Hex;
import io.jeth.util.Keccak;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Ethereum wallet: secp256k1 key generation, ECDSA signing, and EIP-55 address derivation.
 *
 * <p>Signing uses RFC 6979 deterministic k (via BouncyCastle {@code HMacDSAKCalculator})
 * and normalises {@code s} to low-s form per EIP-2, so signatures are valid on all EVM chains.
 *
 * <pre>
 * // Generate
 * Wallet w = Wallet.create();
 * System.out.println(w.getAddress());    // 0xf39Fd6... (EIP-55 checksummed)
 *
 * // Restore
 * Wallet w = Wallet.fromPrivateKey("0xac0974bec3...");
 *
 * // Sign a 32-byte hash (e.g. a transaction hash or EIP-712 digest)
 * Signature sig = w.sign(keccak256bytes);
 * // sig.r, sig.s (BigInteger) and sig.v (int 0 or 1) ready for use in transactions
 *
 * // EIP-191 personal_sign
 * Signature sig = w.signMessage("hello".getBytes());
 * </pre>
 *
 * <p>For HD wallets (BIP-39 mnemonic / BIP-44 derivation paths), see
 * {@link io.jeth.wallet.HdWallet}.
 *
 * @see io.jeth.wallet.HdWallet
 * @see io.jeth.wallet.Keystore
 * @see io.jeth.crypto.Signature
 */
public class Wallet {

    public static final X9ECParameters CURVE_PARAMS = SECNamedCurves.getByName("secp256k1");
    public static final ECDomainParameters CURVE = new ECDomainParameters(
            CURVE_PARAMS.getCurve(), CURVE_PARAMS.getG(), CURVE_PARAMS.getN(), CURVE_PARAMS.getH());

    private final BigInteger privateKey;
    private final byte[]     publicKey;   // 65-byte uncompressed
    private final String     address;     // EIP-55 checksum address

    private Wallet(BigInteger privateKey) {
        this.privateKey = privateKey;
        this.publicKey  = derivePublicKey(privateKey);
        this.address    = deriveAddress(publicKey);
    }

    /**
     * Generates a cryptographically random wallet using {@link java.security.SecureRandom}.
     * Rejects keys outside the secp256k1 curve order (vanishingly rare; retries automatically).
     */
    public static Wallet create() {
        SecureRandom rng = new SecureRandom();
        BigInteger priv;
        do {
            byte[] bytes = new byte[32];
            rng.nextBytes(bytes);
            priv = new BigInteger(1, bytes);
        } while (priv.compareTo(CURVE.getN()) >= 0 || priv.signum() == 0);
        return new Wallet(priv);
    }

    /**
     * Restores a wallet from a hex-encoded private key.
     *
     * @param hex 64 hex chars, with or without {@code 0x} prefix
     */
    public static Wallet fromPrivateKey(String hex) {
        return new Wallet(Hex.toBigInteger(hex));
    }

    public static Wallet fromPrivateKey(byte[] bytes) {
        return new Wallet(new BigInteger(1, bytes));
    }

    // ─── Signing ─────────────────────────────────────────────────────────────

    /**
     * Signs a pre-hashed 32-byte digest with this wallet's private key.
     *
     * <p>The input must already be Keccak-256 hashed. To sign an arbitrary
     * message with the Ethereum personal-sign prefix, use {@link #signMessage(byte[])}.
     *
     * @param messageHash exactly 32 bytes
     * @return {@link Signature} with {@code r}, {@code s}, and recovery {@code v} (0 or 1)
     */
    public Signature sign(byte[] messageHash) {
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, new ECPrivateKeyParameters(privateKey, CURVE));
        BigInteger[] rs = signer.generateSignature(messageHash);
        BigInteger r = rs[0];
        BigInteger s = rs[1];

        // Normalize s to low-s form (EIP-2)
        BigInteger halfN = CURVE.getN().shiftRight(1);
        if (s.compareTo(halfN) > 0) s = CURVE.getN().subtract(s);

        return new Signature(r, s, calculateRecoveryId(messageHash, r, s));
    }

    /**
     * Signs {@code message} using EIP-191 personal sign.
     *
     * <p>Prepends {@code "\x19Ethereum Signed Message:\n" + message.length} before
     * hashing with Keccak-256, matching the behaviour of MetaMask {@code personal_sign}
     * and ethers.js {@code wallet.signMessage()}.
     *
     * @param message arbitrary bytes — the prefix and hashing are applied automatically
     */
    public Signature signMessage(byte[] message) {
        return sign(hashPersonalMessage(message));
    }

    public Signature signMessage(String message) {
        return signMessage(message.getBytes(StandardCharsets.UTF_8));
    }

    /** EIP-191 personal message hash. */
    public static byte[] hashPersonalMessage(byte[] message) {
        byte[] prefix = ("\u0019Ethereum Signed Message:\n" + message.length)
                .getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[prefix.length + message.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(message, 0, combined, prefix.length, message.length);
        return Keccak.hash(combined);
    }

    // ─── Recovery ────────────────────────────────────────────────────────────

    /**
     * Recover the signer address from a message hash + signature.
     * Signature v should be 0/1 (raw) or 27/28 (eth_sign).
     */
    public static String recoverAddress(byte[] messageHash, Signature sig) {
        // Normalise v to raw recovery ID (0 or 1)
        int recId = sig.v >= 27 ? sig.v - 27 : sig.v;
        // secp256k1: recId 0 and 1 are the only valid choices in practice
        if (recId < 0 || recId > 3) throw new IllegalArgumentException("Invalid v/recId: " + sig.v);
        ECPoint pub = recoverPublicKey(recId, sig.r, sig.s, messageHash);
        if (pub != null) return pubToAddress(pub);
        // Fallback: try complementary recId (for rare edge cases)
        ECPoint pub2 = recoverPublicKey(recId ^ 1, sig.r, sig.s, messageHash);
        if (pub2 != null) return pubToAddress(pub2);
        throw new IllegalArgumentException("Could not recover public key for signature");
    }

    /**
     * Recover signer from an EIP-191 personal message (string).
     * Matches eth_sign / MetaMask signatures.
     */
    public static String recoverPersonalMessage(String message, Signature sig) {
        byte[] hash = hashPersonalMessage(
                message.getBytes(StandardCharsets.UTF_8));
        return recoverAddress(hash, sig);
    }

    public static String recoverPersonalMessage(String message, String sigHex) {
        return recoverPersonalMessage(message, parseSignature(sigHex));
    }

    /** Parse a 65-byte 0x-prefixed signature hex into a Signature. */
    public static Signature parseSignature(String hexSig) {
        byte[] bytes = Hex.decode(hexSig);
        if (bytes.length != 65) throw new IllegalArgumentException(
                "Signature must be 65 bytes, got: " + bytes.length);
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(bytes, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(bytes, 32, 64));
        int v        = bytes[64] & 0xFF;
        return new Signature(r, s, v);
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private int calculateRecoveryId(byte[] hash, BigInteger r, BigInteger s) {
        for (int i = 0; i < 4; i++) {
            ECPoint pub = recoverPublicKey(i, r, s, hash);
            if (pub != null && pubToAddress(pub).equalsIgnoreCase(address)) return i;
        }
        return 0;
    }

    private static ECPoint recoverPublicKey(int recId, BigInteger r, BigInteger s, byte[] hash) {
        BigInteger n     = CURVE.getN();
        BigInteger prime = CURVE_PARAMS.getCurve().getField().getCharacteristic();
        BigInteger x     = r.add(BigInteger.valueOf((long)(recId / 2)).multiply(n));
        if (x.compareTo(prime) >= 0) return null;

        byte[] xBytes = to32Bytes(x);
        byte[] comp   = new byte[33];
        comp[0] = (byte)(0x02 + (recId & 1));
        System.arraycopy(xBytes, 0, comp, 1, 32);

        ECPoint R;
        try { R = CURVE_PARAMS.getCurve().decodePoint(comp); }
        catch (Exception e) { return null; }

        BigInteger hi  = new BigInteger(1, hash);
        BigInteger rInv = r.modInverse(n);
        BigInteger u1  = rInv.multiply(n.subtract(hi)).mod(n);
        BigInteger u2  = rInv.multiply(s).mod(n);
        return CURVE.getG().multiply(u1).add(R.multiply(u2)).normalize();
    }

    private static String pubToAddress(ECPoint pub) {
        byte[] raw64 = new byte[64];
        byte[] encoded = pub.getEncoded(false);
        System.arraycopy(encoded, 1, raw64, 0, 64);
        byte[] hash = Keccak.hash(raw64);
        byte[] addr = new byte[20];
        System.arraycopy(hash, 12, addr, 0, 20);
        return Address.toChecksumAddress(Hex.encodeNoPrefx(addr));
    }

    private static byte[] derivePublicKey(BigInteger priv) {
        return CURVE.getG().multiply(priv).normalize().getEncoded(false);
    }

    private static String deriveAddress(byte[] pubKey) {
        byte[] raw64 = new byte[64];
        System.arraycopy(pubKey, 1, raw64, 0, 64);
        byte[] hash = Keccak.hash(raw64);
        byte[] addr = new byte[20];
        System.arraycopy(hash, 12, addr, 0, 20);
        return Address.toChecksumAddress(Hex.encodeNoPrefx(addr));
    }

    static byte[] to32Bytes(BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length == 32) return raw;
        byte[] out = new byte[32];
        if (raw.length < 32) System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        else System.arraycopy(raw, raw.length - 32, out, 0, 32);
        return out;
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    public String     getAddress()        { return address; }
    public BigInteger getPrivateKey()     { return privateKey; }
    public String     getPrivateKeyHex()  { return Hex.fromBigInteger(privateKey); }
    public byte[]     getPublicKeyBytes() { return Arrays.copyOf(publicKey, publicKey.length); }
    public String     getPublicKeyHex()   { return Hex.encode(publicKey); }

    @Override public String toString() { return "Wallet{" + address + "}"; }

    // ─── Connected signer ─────────────────────────────────────────────────────

    /**
     * Bind this wallet to a client — like ethers.js {@code wallet.connect(provider)}.
     *
     * <pre>
     * var signer = wallet.connect(client);
     * String txHash = signer.sendEth("0xRecipient", Units.toWei("0.1")).join();
     * String txHash = signer.sendTransaction("0xContract", calldata).join();
     * </pre>
     */
    public ConnectedWallet connect(EthClient client) {
        return new ConnectedWallet(this, client);
    }

    /**
     * A wallet bound to an EthClient for one-line sign-and-send.
     * Handles nonce, gas estimation, chain ID, and EIP-1559 fees automatically.
     */
    public static class ConnectedWallet {
        private final Wallet               wallet;
        private final EthClient client;

        public ConnectedWallet(Wallet wallet, EthClient client) {
            this.wallet = wallet;
            this.client = client;
        }

        /** Send ETH to {@code to}. Returns tx hash. */
        public CompletableFuture<String> sendEth(
                String to, BigInteger valueWei) {
            return buildAndSend(to, valueWei, null);
        }

        /** Send a contract call (with calldata). Gas estimated automatically. Returns tx hash. */
        public CompletableFuture<String> sendTransaction(
                String to, String calldata) {
            return buildAndSend(to, BigInteger.ZERO, calldata);
        }

        /** Send a payable contract call. Returns tx hash. */
        public CompletableFuture<String> sendTransaction(
                String to, BigInteger value, String calldata) {
            return buildAndSend(to, value, calldata);
        }

        private CompletableFuture<String> buildAndSend(
                String to, BigInteger value, String calldata) {
            return client.getChainId()
                .thenCompose(chainId -> client.getTransactionCount(wallet.getAddress())
                .thenCompose(nonce -> {
                    EthModels.CallRequest req =
                            EthModels.CallRequest.builder()
                            .from(wallet.getAddress()).to(to).value(value).data(calldata).build();
                    return client.estimateGas(req)
                        .thenCompose(gas -> client.getBlock("latest")
                        .thenCompose(block -> client.getMaxPriorityFeePerGas()
                        .thenApply(tip -> {
                            BigInteger base = block.baseFeePerGas != null
                                    ? block.baseFeePerGas : BigInteger.ZERO;
                            BigInteger maxFee = base.multiply(BigInteger.TWO).add(tip);
                            // Add 20% gas buffer
                            BigInteger gasWithBuffer = gas
                                    .multiply(BigInteger.valueOf(12))
                                    .divide(BigInteger.TEN);
                            EthModels.TransactionRequest tx =
                                    EthModels.TransactionRequest.builder()
                                    .from(wallet.getAddress()).to(to).value(value).data(calldata)
                                    .gas(gasWithBuffer).maxFeePerGas(maxFee)
                                    .maxPriorityFeePerGas(tip).nonce(nonce).chainId(chainId).build();
                            return TransactionSigner.signEip1559(tx, wallet);
                        })));
                }))
                .thenCompose(client::sendRawTransaction);
        }

        public Wallet                  getWallet()   { return wallet; }
        public EthClient  getClient()   { return client; }
        public String                  getAddress()  { return wallet.getAddress(); }

        @Override public String toString() {
            return "ConnectedWallet{address=" + wallet.getAddress() + "}";
        }
    }
}
