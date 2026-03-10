/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth;

import io.jeth.gas.GasEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GasEstimatorTest {

    static final String FEE_HISTORY = """
        {"oldestBlock":"0x1","baseFeePerGas":["0x12a05f200","0x11e1a300"],
         "gasUsedRatio":[0.85,0.72],
         "reward":[["0x3b9aca00","0x77359400","0xee6b2800"],
                   ["0x3b9aca00","0x77359400","0xee6b2800"]]}""";

    static final String BLOCK_JSON = """
        {"number":"0x1","hash":"0xabc","timestamp":"0x0",
         "baseFeePerGas":"0x12a05f200","gasLimit":"0x1c9c380","gasUsed":"0x0","transactions":[]}""";

    @Test @DisplayName("suggest() returns 3 tiers: low, medium, high")
    void three_tiers() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(FEE_HISTORY).enqueueJson(BLOCK_JSON);
            var fees = GasEstimator.of(rpc.client()).suggest().join();
            assertNotNull(fees.low());
            assertNotNull(fees.medium());
            assertNotNull(fees.high());
        }
    }

    @Test @DisplayName("low ≤ medium ≤ high maxFeePerGas")
    void ordering() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(FEE_HISTORY).enqueueJson(BLOCK_JSON);
            var fees = GasEstimator.of(rpc.client()).suggest().join();
            assertTrue(fees.low().maxFeePerGas().compareTo(fees.medium().maxFeePerGas()) <= 0);
            assertTrue(fees.medium().maxFeePerGas().compareTo(fees.high().maxFeePerGas()) <= 0);
        }
    }

    @Test @DisplayName("all gwei values > 0")
    void positive_gwei() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(FEE_HISTORY).enqueueJson(BLOCK_JSON);
            var fees = GasEstimator.of(rpc.client()).suggest().join();
            assertTrue(fees.low().maxFeeGwei() > 0);
            assertTrue(fees.medium().maxFeeGwei() > 0);
            assertTrue(fees.high().maxFeeGwei() > 0);
        }
    }

    @Test @DisplayName("all ETAs non-null and non-empty")
    void etas() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(FEE_HISTORY).enqueueJson(BLOCK_JSON);
            var fees = GasEstimator.of(rpc.client()).suggest().join();
            assertNotNull(fees.low().eta());
            assertNotNull(fees.medium().eta());
            assertNotNull(fees.high().eta());
            assertFalse(fees.low().eta().isEmpty());
        }
    }

    @Test @DisplayName("toString() contains 'gwei'")
    void to_string() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(FEE_HISTORY).enqueueJson(BLOCK_JSON);
            var fees = GasEstimator.of(rpc.client()).suggest().join();
            assertTrue(fees.medium().toString().contains("gwei"));
        }
    }

    @Test @DisplayName("baseFeeGwei() returns positive value")
    void base_fee_gwei() throws Exception {
        try (var rpc = new RpcMock()) {
            rpc.enqueueJson(FEE_HISTORY).enqueueJson(BLOCK_JSON);
            assertTrue(GasEstimator.of(rpc.client()).suggest().join().baseFeeGwei() > 0);
        }
    }
}
