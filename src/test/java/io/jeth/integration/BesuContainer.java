/*
 * Copyright (c) jeth contributors
 * SPDX-License-Identifier: MIT
 */
package io.jeth.integration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;

/**
 * Testcontainers wrapper for a Hyperledger Besu dev node.
 *
 * Started automatically by @Testcontainers when {@link BesuIntegrationTest} runs.
 * Requires Docker to be available — tests are tagged "integration" and skipped otherwise.
 */
public class BesuContainer extends GenericContainer<BesuContainer> {

    public static final String DEV_PRIVATE_KEY =
        "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    public static final String DEV_ADDRESS =
        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";

    static final int HTTP = 8545;
    static final int WS   = 8546;

    public BesuContainer() {
        super(DockerImageName.parse("hyperledger/besu:latest"));
        withExposedPorts(HTTP, WS);
        withCommand(
            "--network=dev",
            "--rpc-http-enabled=true", "--rpc-http-host=0.0.0.0",
            "--rpc-http-port=" + HTTP,
            "--rpc-ws-enabled=true",   "--rpc-ws-host=0.0.0.0",
            "--rpc-ws-port="   + WS,
            "--rpc-http-api=ETH,NET,WEB3,DEBUG,MINER,TXPOOL",
            "--host-allowlist=*", "--rpc-http-cors-origins=all",
            "--miner-enabled=true", "--miner-coinbase=" + DEV_ADDRESS,
            "--min-gas-price=0", "--logging=WARN"
        );
        waitingFor(new HttpWaitStrategy().forPort(HTTP).forPath("/")
            .withMethod("POST").allowInsecure()
            .withStartupTimeout(Duration.ofSeconds(90)));
    }

    public String httpUrl() { return "http://" + getHost() + ":" + getMappedPort(HTTP); }
    public String wsUrl()   { return "ws://"   + getHost() + ":" + getMappedPort(WS);   }
}
