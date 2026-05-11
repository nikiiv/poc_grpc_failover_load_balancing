package com.example.poc.server;

import com.example.poc.registry.RegisterRequest;
import com.example.poc.registry.RegisterResponse;
import com.example.poc.registry.RegistryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Registers this backend with the BFF's RegistryService on startup. Retries with
 * exponential backoff until accepted (covers BFF not yet up at boot time).
 */
final class RegistrationClient {

    private static final Logger LOG = LoggerFactory.getLogger(RegistrationClient.class);

    private final String bffTarget;
    private final String serverId;
    private final String advertisedHost;
    private final int advertisedPort;

    RegistrationClient(String bffTarget, String serverId, String advertisedHost, int advertisedPort) {
        this.bffTarget = bffTarget;
        this.serverId = serverId;
        this.advertisedHost = advertisedHost;
        this.advertisedPort = advertisedPort;
    }

    void registerWithRetry() {
        long delayMs = 250;
        long maxDelayMs = 5_000;
        while (!Thread.currentThread().isInterrupted()) {
            ManagedChannel ch = NettyChannelBuilder.forTarget(bffTarget)
                    .usePlaintext()
                    .build();
            try {
                RegisterResponse resp = RegistryServiceGrpc.newBlockingStub(ch)
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .registerServer(RegisterRequest.newBuilder()
                                .setServerId(serverId)
                                .setHost(advertisedHost)
                                .setPort(advertisedPort)
                                .build());
                if (resp.getAccepted()) {
                    LOG.info("Registered with BFF {}: {}", bffTarget, resp.getMessage());
                    return;
                }
                LOG.warn("BFF rejected registration: {} — retrying", resp.getMessage());
            } catch (Exception e) {
                LOG.info("Registration attempt to {} failed ({}) — retrying in {} ms",
                        bffTarget, e.getMessage(), delayMs);
            } finally {
                ch.shutdownNow();
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            delayMs = Math.min(maxDelayMs, delayMs * 2);
        }
    }
}
