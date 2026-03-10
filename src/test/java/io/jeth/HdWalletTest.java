/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import static org.junit.jupiter.api.Assertions.*;

import io.jeth.util.Address;
import io.jeth.wallet.HdWallet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HD Wallet BIP-32/39/44 tests. Expected addresses verified against MetaMask / ethers.js. */
class HdWalletTest {

    static final String MNEMONIC = "test test test test test test test test test test test junk";

    static final String[] EXPECTED = {
        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
        "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
        "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC",
        "0x90F79bf6EB2c4f870365E785982E1f101E93b906",
        "0x15d34AAf54267DB7D7c367839AAf71A00a2C6A65",
    };

    @Test
    @DisplayName("Known mnemonic derives correct addresses (0-4)")
    void known_mnemonic() {
        var hd = HdWallet.fromMnemonic(MNEMONIC);
        for (int i = 0; i < EXPECTED.length; i++)
            assertEquals(EXPECTED[i], hd.getAccount(i).getAddress(), "account " + i);
    }

    @Test
    @DisplayName("Derivation is deterministic")
    void deterministic() {
        assertEquals(
                HdWallet.fromMnemonic(MNEMONIC).getAccount(0).getAddress(),
                HdWallet.fromMnemonic(MNEMONIC).getAccount(0).getAddress());
    }

    @Test
    @DisplayName("getMnemonic() round-trips")
    void mnemonic_preserved() {
        assertEquals(MNEMONIC, HdWallet.fromMnemonic(MNEMONIC).getMnemonic());
    }

    @Test
    @DisplayName("generate() produces valid 12-word mnemonic")
    void generate_12() {
        var hd = HdWallet.generate();
        assertEquals(12, hd.getMnemonic().split("\\s+").length);
        assertTrue(Address.isValid(hd.getAccount(0).getAddress()));
    }

    @Test
    @DisplayName("generate(256) produces 24-word mnemonic")
    void generate_24() {
        assertEquals(24, HdWallet.generate(256).getMnemonic().split("\\s+").length);
    }

    @Test
    @DisplayName("Each generate() produces unique mnemonic")
    void generate_unique() {
        assertNotEquals(HdWallet.generate().getMnemonic(), HdWallet.generate().getMnemonic());
    }

    @Test
    @DisplayName("Generated wallet restores from its own mnemonic")
    void generate_and_restore() {
        var orig = HdWallet.generate();
        var addr = orig.getAccount(0).getAddress();
        assertEquals(addr, HdWallet.fromMnemonic(orig.getMnemonic()).getAccount(0).getAddress());
    }

    @Test
    @DisplayName("getAccounts(5) returns 5 distinct valid addresses")
    void get_accounts() {
        var accounts = HdWallet.fromMnemonic(MNEMONIC).getAccounts(5);
        assertEquals(5, accounts.size());
        var addrs = accounts.stream().map(w -> w.getAddress()).collect(Collectors.toSet());
        assertEquals(5, addrs.size(), "All addresses must be distinct");
    }

    @Test
    @DisplayName("derive(path) == getAccount(0) for standard path")
    void derive_standard_path() {
        var hd = HdWallet.fromMnemonic(MNEMONIC);
        assertEquals(hd.getAccount(0).getAddress(), hd.derive("m/44'/60'/0'/0/0").getAddress());
    }

    @Test
    @DisplayName("Passphrase changes derived addresses")
    void passphrase() {
        var noPass = HdWallet.fromMnemonic(MNEMONIC, "");
        var withPass = HdWallet.fromMnemonic(MNEMONIC, "secret");
        assertNotEquals(noPass.getAccount(0).getAddress(), withPass.getAccount(0).getAddress());
    }

    @Test
    @DisplayName("fromSeed round-trips")
    void from_seed() {
        var hd = HdWallet.fromMnemonic(MNEMONIC);
        assertEquals(
                hd.getAccount(0).getAddress(),
                HdWallet.fromSeed(hd.getSeed()).getAccount(0).getAddress());
    }
}
