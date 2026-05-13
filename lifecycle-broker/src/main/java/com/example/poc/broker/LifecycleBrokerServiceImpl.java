package com.example.poc.broker;

import com.example.poc.lifecycle.Ack;
import com.example.poc.lifecycle.LifecycleBrokerGrpc;
import com.example.poc.lifecycle.LifecycleEvent;
import com.example.poc.lifecycle.NodeInfo;
import com.example.poc.lifecycle.NodeRef;
import com.example.poc.lifecycle.SubscribeRequest;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LifecycleBrokerServiceImpl extends LifecycleBrokerGrpc.LifecycleBrokerImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleBrokerServiceImpl.class);

    /** All nodes currently known to the broker. */
    private final ConcurrentHashMap<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    /** Active outbound streams keyed by a per-subscription UUID. */
    private final ConcurrentHashMap<String, Subscription> subs = new ConcurrentHashMap<>();

    private record Subscription(String subscriberNodeId, StreamObserver<LifecycleEvent> stream) {}

    /**
     * Pre-populate the in-memory nodes map. Must be called BEFORE the gRPC server starts
     * accepting Subscribe calls — there's no broadcast for seeded nodes, since by
     * definition no one is subscribed yet.
     */
    void seed(java.util.Collection<NodeInfo> initial) {
        for (NodeInfo n : initial) {
            nodes.putIfAbsent(n.getNodeId(), n);
        }
    }

    @Override
    public void announce(NodeInfo req, StreamObserver<Ack> resp) {
        if (req.getNodeId().isEmpty()) {
            resp.onNext(Ack.newBuilder().setAccepted(false).setMessage("node_id is required").build());
            resp.onCompleted();
            return;
        }
        NodeInfo previous = nodes.put(req.getNodeId(), req);
        LOG.info("ANNOUNCE {} kind={} role={} address={}{}",
                req.getNodeId(), req.getKind(), req.getRole(), req.getAddress(),
                previous == null ? "" : " (replaced)");
        // Fire LEFT for the replaced node first, then JOINED for the fresh one.
        if (previous != null) {
            broadcast(LifecycleEvent.newBuilder()
                    .setType(LifecycleEvent.Type.LEFT)
                    .setNode(previous)
                    .build());
        }
        broadcast(LifecycleEvent.newBuilder()
                .setType(LifecycleEvent.Type.JOINED)
                .setNode(req)
                .build());
        resp.onNext(Ack.newBuilder().setAccepted(true).setMessage("announced").build());
        resp.onCompleted();
    }

    @Override
    public void withdraw(NodeRef req, StreamObserver<Ack> resp) {
        NodeInfo removed = nodes.remove(req.getNodeId());
        if (removed != null) {
            LOG.info("WITHDRAW {}", req.getNodeId());
            broadcast(LifecycleEvent.newBuilder()
                    .setType(LifecycleEvent.Type.LEFT)
                    .setNode(removed)
                    .build());
        }
        resp.onNext(Ack.newBuilder().setAccepted(true).setMessage("withdrawn").build());
        resp.onCompleted();
    }

    @Override
    public void subscribe(SubscribeRequest req, StreamObserver<LifecycleEvent> resp) {
        String subscriberId = req.getSubscriberId();
        String subKey = subscriberId.isEmpty()
                ? "anon-" + UUID.randomUUID()
                : subscriberId + "-" + UUID.randomUUID();
        LOG.info("SUBSCRIBE from {} (key={})", subscriberId, subKey);

        ServerCallStreamObserver<LifecycleEvent> server = (ServerCallStreamObserver<LifecycleEvent>) resp;
        // Send snapshot synchronously, then register for live events.
        for (NodeInfo n : nodes.values()) {
            server.onNext(LifecycleEvent.newBuilder()
                    .setType(LifecycleEvent.Type.SNAPSHOT_ITEM)
                    .setNode(n)
                    .build());
        }
        server.onNext(LifecycleEvent.newBuilder()
                .setType(LifecycleEvent.Type.SNAPSHOT_END)
                .build());

        subs.put(subKey, new Subscription(subscriberId, server));
        server.setOnCancelHandler(() -> handleDisconnect(subKey, "client cancelled"));
        server.setOnCloseHandler(() -> handleDisconnect(subKey, "stream closed"));
    }

    private void handleDisconnect(String subKey, String why) {
        Subscription s = subs.remove(subKey);
        if (s == null) return;
        LOG.info("UNSUBSCRIBE {} ({})", subKey, why);
        // If the subscriber identified itself, treat its disappearance as that node
        // leaving. (Backends don't subscribe, so this fires only for BFFs.)
        if (!s.subscriberNodeId().isEmpty()) {
            NodeInfo removed = nodes.remove(s.subscriberNodeId());
            if (removed != null) {
                LOG.info("Subscriber {} disconnected — emitting LEFT", s.subscriberNodeId());
                broadcast(LifecycleEvent.newBuilder()
                        .setType(LifecycleEvent.Type.LEFT)
                        .setNode(removed)
                        .build());
            }
        }
    }

    private void broadcast(LifecycleEvent event) {
        for (Map.Entry<String, Subscription> e : subs.entrySet()) {
            try {
                e.getValue().stream().onNext(event);
            } catch (Exception ex) {
                LOG.warn("Subscriber {} send failed ({}); dropping", e.getKey(), ex.toString());
                subs.remove(e.getKey());
            }
        }
    }
}
