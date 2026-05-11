package com.example.poc.bff.broker;

import com.example.poc.lifecycle.Ack;
import com.example.poc.lifecycle.LifecycleBrokerGrpc;
import com.example.poc.lifecycle.NodeInfo;
import com.example.poc.lifecycle.NodeKind;
import com.example.poc.lifecycle.NodeRef;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Eagerly announces this BFF (kind=BFF) on startup; withdraws on graceful shutdown.
 * Belt-and-braces with the broker's auto-LEFT-on-subscription-disconnect.
 */
@Context
public final class BrokerAnnouncer {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerAnnouncer.class);

    private final String brokerTarget;
    private final String role;
    private final String nodeId;
    private final String address;

    public BrokerAnnouncer(@Value("${poc.bff.brokerTarget}") String brokerTarget,
                           @Value("${poc.bff.role}") String role,
                           @Value("${poc.bff.nodeId}") String nodeId,
                           @Value("${poc.bff.advertisedAddress}") String address) {
        this.brokerTarget = brokerTarget;
        this.role = role;
        this.nodeId = nodeId;
        this.address = address;
    }

    @PostConstruct
    void start() {
        Thread t = new Thread(this::announceWithRetry, "broker-announcer");
        t.setDaemon(true);
        t.start();
    }

    @PreDestroy
    void stop() {
        withdraw();
    }

    private void announceWithRetry() {
        long delayMs = 250;
        long maxDelayMs = 5_000;
        while (!Thread.currentThread().isInterrupted()) {
            ManagedChannel ch = newChannel();
            try {
                Ack ack = LifecycleBrokerGrpc.newBlockingStub(ch)
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .announce(NodeInfo.newBuilder()
                                .setKind(NodeKind.BFF)
                                .setRole(role)
                                .setNodeId(nodeId)
                                .setAddress(address)
                                .build());
                if (ack.getAccepted()) {
                    LOG.info("Announced BFF to broker {}: {}", brokerTarget, ack.getMessage());
                    return;
                }
            } catch (Exception e) {
                LOG.info("Announce to broker {} failed ({}) — retrying in {} ms",
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

    private void withdraw() {
        ManagedChannel ch = newChannel();
        try {
            LifecycleBrokerGrpc.newBlockingStub(ch)
                    .withDeadlineAfter(1, TimeUnit.SECONDS)
                    .withdraw(NodeRef.newBuilder().setNodeId(nodeId).build());
            LOG.info("Withdrew BFF from broker {}", brokerTarget);
        } catch (Exception e) {
            LOG.info("Withdraw failed (best effort): {}", e.getMessage());
        } finally {
            ch.shutdownNow();
        }
    }

    /** Static helper used by the registry to report observed server deaths to the broker. */
    public static void withdrawNode(String brokerTarget, String nodeId) {
        ManagedChannel ch = NettyChannelBuilder.forTarget(brokerTarget).usePlaintext().build();
        try {
            LifecycleBrokerGrpc.newBlockingStub(ch)
                    .withDeadlineAfter(1, TimeUnit.SECONDS)
                    .withdraw(NodeRef.newBuilder().setNodeId(nodeId).build());
            LOG.info("Reported death of {} to broker {}", nodeId, brokerTarget);
        } catch (Exception e) {
            LOG.debug("Death report for {} failed (best effort): {}", nodeId, e.getMessage());
        } finally {
            ch.shutdownNow();
        }
    }

    private ManagedChannel newChannel() {
        return NettyChannelBuilder.forTarget(brokerTarget).usePlaintext().build();
    }
}
