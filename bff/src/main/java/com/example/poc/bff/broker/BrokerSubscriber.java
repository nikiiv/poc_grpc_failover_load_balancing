package com.example.poc.bff.broker;

import com.example.poc.bff.registry.KnownNodesRegistry;
import com.example.poc.bff.registry.ServerRegistry;
import com.example.poc.lifecycle.LifecycleBrokerGrpc;
import com.example.poc.lifecycle.LifecycleEvent;
import com.example.poc.lifecycle.NodeInfo;
import com.example.poc.lifecycle.NodeKind;
import com.example.poc.lifecycle.SubscribeRequest;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * BFF-side consumer of the lifecycle broker. Maintains a long-lived Subscribe stream and
 * dispatches JOINED / LEFT events into the registry. Reconnects with backoff on stream
 * errors so a broker restart doesn't permanently disconnect us.
 *
 * <p>Slice 7a: trusts the broker for any SERVER event whose role matches our own; ignores
 * other roles and ignores BFF events. Slice 7b will keep a passive "known nodes" map for
 * cross-role observability.
 */
// @Context = Micronaut "eager singleton" scope (started at app boot). Disambiguated from
// io.grpc.Context with the FQN.
@io.micronaut.context.annotation.Context
public final class BrokerSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerSubscriber.class);

    private final String brokerTarget;
    private final String role;
    private final String nodeId;
    private final ServerRegistry registry;
    private final KnownNodesRegistry knownNodes;

    private volatile ManagedChannel channel;
    private volatile io.grpc.Context.CancellableContext subContext;
    private volatile boolean shuttingDown;

    public BrokerSubscriber(@Value("${poc.bff.brokerTarget}") String brokerTarget,
                            @Value("${poc.bff.role}") String role,
                            @Value("${poc.bff.nodeId}") String nodeId,
                            ServerRegistry registry,
                            KnownNodesRegistry knownNodes) {
        this.brokerTarget = brokerTarget;
        this.role = role;
        this.nodeId = nodeId;
        this.registry = registry;
        this.knownNodes = knownNodes;
    }

    @PostConstruct
    void start() {
        Thread t = new Thread(this::runWithReconnect, "broker-subscriber");
        t.setDaemon(true);
        t.start();
    }

    @PreDestroy
    void stop() {
        shuttingDown = true;
        io.grpc.Context.CancellableContext c = subContext;
        if (c != null) {
            c.cancel(Status.CANCELLED.asRuntimeException());
        }
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    private void runWithReconnect() {
        long delayMs = 250;
        while (!shuttingDown && !Thread.currentThread().isInterrupted()) {
            try {
                subscribeOnce();
                LOG.info("Broker subscription ended; reconnecting");
            } catch (Exception e) {
                LOG.info("Broker subscription error ({}); reconnecting in {} ms", e.getMessage(), delayMs);
            }
            if (shuttingDown) return;
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            delayMs = Math.min(5_000, delayMs * 2);
        }
    }

    private void subscribeOnce() throws InterruptedException {
        channel = NettyChannelBuilder.forTarget(brokerTarget)
                .usePlaintext()
                .keepAliveTime(5, TimeUnit.SECONDS)
                .keepAliveTimeout(2, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();

        Object doneLock = new Object();
        boolean[] done = { false };

        StreamObserver<LifecycleEvent> observer = new StreamObserver<>() {
            @Override
            public void onNext(LifecycleEvent ev) {
                handle(ev);
            }
            @Override
            public void onError(Throwable t) {
                LOG.info("Subscription stream error: {}", t.getMessage());
                synchronized (doneLock) {
                    done[0] = true;
                    doneLock.notifyAll();
                }
            }
            @Override
            public void onCompleted() {
                synchronized (doneLock) {
                    done[0] = true;
                    doneLock.notifyAll();
                }
            }
        };

        // Context.ROOT detaches from any caller's RPC context (we have none, but stay
        // consistent with the pattern used in HealthWatcher).
        io.grpc.Context.CancellableContext ctx = io.grpc.Context.ROOT.withCancellation();
        subContext = ctx;
        ctx.run(() -> LifecycleBrokerGrpc.newStub(channel)
                .subscribe(SubscribeRequest.newBuilder().setSubscriberId(nodeId).build(), observer));

        synchronized (doneLock) {
            while (!shuttingDown && !done[0]) {
                doneLock.wait();
            }
        }
        ctx.cancel(Status.CANCELLED.asRuntimeException());
        channel.shutdownNow();
    }

    private void handle(LifecycleEvent ev) {
        NodeInfo node = ev.getNode();
        switch (ev.getType()) {
            case SNAPSHOT_ITEM, JOINED -> onJoinOrSnapshot(node);
            case LEFT -> onLeave(node);
            case SNAPSHOT_END -> LOG.info("Broker snapshot complete");
            default -> { /* TYPE_UNSPECIFIED — ignore */ }
        }
    }

    private void onJoinOrSnapshot(NodeInfo node) {
        if (node.getNodeId().isEmpty()) return;

        // Step 1: ALWAYS record in the passive known-nodes ledger.
        knownNodes.record(
                node.getNodeId(),
                node.getKind().name(),
                node.getRole(),
                node.getAddress());

        // Step 2: route only same-role SERVER entries.
        if (node.getKind() != NodeKind.SERVER) return;
        if (!node.getRole().equals(role)) return;
        if (node.getAddress().isEmpty()) return;
        String[] hp = node.getAddress().split(":");
        if (hp.length != 2) {
            LOG.warn("Bad address from broker: {}", node.getAddress());
            return;
        }
        try {
            int port = Integer.parseInt(hp[1]);
            registry.register(node.getNodeId(), hp[0], port);
        } catch (NumberFormatException e) {
            LOG.warn("Bad port from broker: {}", node.getAddress());
        }
    }

    private void onLeave(NodeInfo node) {
        if (node.getNodeId().isEmpty()) return;
        knownNodes.forget(node.getNodeId());
        registry.removeById(node.getNodeId());
    }
}
