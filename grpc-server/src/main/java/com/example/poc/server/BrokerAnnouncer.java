package com.example.poc.server;

import com.example.poc.lifecycle.Ack;
import com.example.poc.lifecycle.LifecycleBrokerGrpc;
import com.example.poc.lifecycle.NodeInfo;
import com.example.poc.lifecycle.NodeKind;
import com.example.poc.lifecycle.NodeRef;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Backend side of the pub/sub control plane. Announces this server on startup; supports
 * an explicit Withdraw on graceful shutdown. Retries with exponential backoff until the
 * broker accepts (covers boot ordering).
 */
final class BrokerAnnouncer {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerAnnouncer.class);

    private final String brokerTarget;
    private final String role;
    private final String nodeId;
    private final String advertisedAddress;

    BrokerAnnouncer(String brokerTarget, String role, String nodeId, String advertisedAddress) {
        this.brokerTarget = brokerTarget;
        this.role = role;
        this.nodeId = nodeId;
        this.advertisedAddress = advertisedAddress;
    }

    void announceWithRetry() {
        long delayMs = 250;
        long maxDelayMs = 5_000;
        while (!Thread.currentThread().isInterrupted()) {
            ManagedChannel ch = newChannel();
            try {
                Ack ack = LifecycleBrokerGrpc.newBlockingStub(ch)
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .announce(NodeInfo.newBuilder()
                                .setKind(NodeKind.SERVER)
                                .setRole(role)
                                .setNodeId(nodeId)
                                .setAddress(advertisedAddress)
                                .build());
                if (ack.getAccepted()) {
                    LOG.info("Announced to broker {}: {}", brokerTarget, ack.getMessage());
                    return;
                }
                LOG.warn("Broker rejected announce: {} — retrying", ack.getMessage());
            } catch (Exception e) {
                LOG.info("Announce to {} failed ({}) — retrying in {} ms",
                        brokerTarget, e.getMessage(), delayMs);
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

    /** Best-effort graceful withdrawal — fired from the shutdown hook. */
    void withdraw() {
        ManagedChannel ch = newChannel();
        try {
            LifecycleBrokerGrpc.newBlockingStub(ch)
                    .withDeadlineAfter(1, TimeUnit.SECONDS)
                    .withdraw(NodeRef.newBuilder().setNodeId(nodeId).build());
            LOG.info("Withdrew from broker {}", brokerTarget);
        } catch (Exception e) {
            LOG.info("Withdraw failed (best effort): {}", e.getMessage());
        } finally {
            ch.shutdownNow();
        }
    }

    private ManagedChannel newChannel() {
        return NettyChannelBuilder.forTarget(brokerTarget)
                .usePlaintext()
                .build();
    }
}
