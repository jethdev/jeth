/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.util.Units;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Extended Units tests beyond what UtilTest covers. */
class UnitsExtendedTest {

    @Test @DisplayName("toWei(double) round-trips for common values")
    void to_wei_double() {
        assertEquals(new BigInteger("1000000000000000000"), Units.toWei(1.0));
        assertEquals(new BigInteger("500000000000000000"),  Units.toWei(0.5));
        assertEquals(new BigInteger("100000000000000000"),  Units.toWei(0.1));
    }

    @Test @DisplayName("formatEther with max decimals=2")
    void format_ether_trimmed() {
        BigInteger oneEth = Units.toWei("1.0");
        assertEquals("1.0", Units.formatEtherTrimmed(oneEth, 2));
        BigInteger halfEth = Units.toWei("0.5");
        assertEquals("0.5", Units.formatEtherTrimmed(halfEth, 2));
    }

    @Test @DisplayName("parseToken with 6 decimals (USDC)")
    void parse_token_usdc() {
        BigInteger amount = Units.parseToken("1.0", 6);
        assertEquals(BigInteger.valueOf(1_000_000), amount);
    }

    @Test @DisplayName("parseToken with 18 decimals")
    void parse_token_18() {
        BigInteger amount = Units.parseToken("1.5", 18);
        assertEquals(new BigInteger("1500000000000000000"), amount);
    }

    @Test @DisplayName("formatToken with 6 decimals")
    void format_token_6() {
        assertEquals("1.0", Units.formatToken(BigInteger.valueOf(1_000_000), 6));
    }

    @Test @DisplayName("formatGwei converts wei to gwei string")
    void format_gwei() {
        assertEquals("30.0", Units.formatGwei(BigInteger.valueOf(30_000_000_000L)));
    }

    @Test @DisplayName("formatGasCostEth returns non-empty string")
    void format_gas_cost() {
        String result = Units.formatGasCostEth(21000, BigInteger.valueOf(30_000_000_000L));
        assertFalse(result.isEmpty());
        assertTrue(result.contains("ETH") || result.contains("eth") || result.matches(".*\\d.*"));
    }

    @Test @DisplayName("MAX_UINT256 is 2^256 - 1")
    void max_uint256() {
        assertEquals(BigInteger.TWO.pow(256).subtract(BigInteger.ONE), Units.MAX_UINT256);
    }

    @Test @DisplayName("toWei / formatEther inverse for small decimals")
    void to_wei_format_inverse() {
        String[] amounts = {"1.0", "0.5", "0.1", "1.5", "100.0"};
        for (String a : amounts)
            assertEquals(a, Units.formatEther(Units.toWei(a)), "Failed for: " + a);
    }
}
