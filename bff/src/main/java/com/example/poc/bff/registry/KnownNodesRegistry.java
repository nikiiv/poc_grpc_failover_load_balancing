package com.example.poc.bff.registry;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passive ledger of every node the broker has told us about — regardless of kind or role.
 * Distinct from {@link ServerRegistry} (which holds only same-role SERVER entries that we
 * actively route to and health-watch). The UI's cluster-topology panel reads from here.
 */
@Singleton
public final class KnownNodesRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(KnownNodesRegistry.class);

    private final ConcurrentHashMap<String, KnownNode> nodes = new ConcurrentHashMap<>();
    private final EventBus events;

    public KnownNodesRegistry(EventBus events) {
        this.events = events;
    }

    public void record(String nodeId, String kind, String role, String address) {
        long now = Instant.now().toEpochMilli();
        KnownNode prev = nodes.get(nodeId);
        long firstSeen = (prev != null) ? prev.firstSeenMs() : now;
        KnownNode fresh = new KnownNode(nodeId, kind, role, address, firstSeen, now);
        nodes.put(nodeId, fresh);
        if (prev == null) {
            LOG.info("Known node JOINED {} ({}/{}) {}", nodeId, kind, role, address);
            events.emit(RegistryEvent.nodeJoined(fresh));
        }
    }

    public void forget(String nodeId) {
        KnownNode removed = nodes.remove(nodeId);
        if (removed != null) {
            LOG.info("Known node LEFT {}", nodeId);
            events.emit(RegistryEvent.nodeLeft(nodeId));
        }
    }

    public Collection<KnownNode> snapshot() {
        return nodes.values();
    }
}
