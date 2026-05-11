package com.example.poc.bff.registry;

import com.example.poc.bff.broker.BrokerAnnouncer;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Singleton
public final class ServerRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ServerRegistry.class);

    private final ConcurrentHashMap<String, ServerEntry> entries = new ConcurrentHashMap<>();
    private final EventBus events;
    private final String brokerTarget;

    public ServerRegistry(EventBus events,
                          @Value("${poc.bff.brokerTarget}") String brokerTarget) {
        this.events = events;
        this.brokerTarget = brokerTarget;
    }

    /**
     * Register (or RE-register) a backend. A second event for the same id means a NEW
     * physical instance (e.g. container restart): the old entry's channel almost
     * certainly points at a dead peer with a stale IP. So we always replace and rebuild
     * the channel + watcher.
     */
    public ServerEntry register(String id, String host, int port) {
        ServerEntry[] previous = new ServerEntry[1];
        ServerEntry fresh = entries.compute(id, (k, existing) -> {
            if (existing != null) {
                previous[0] = existing;
            }
            ManagedChannel ch = NettyChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .keepAliveTime(2, TimeUnit.SECONDS)
                    .keepAliveTimeout(1, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .build();
            return new ServerEntry(id, host, port, ch);
        });

        if (previous[0] != null) {
            LOG.info("Replacing stale entry for {} (was {}:{})", id, previous[0].host(), previous[0].port());
            previous[0].cancelHealth();
            previous[0].channel().shutdownNow();
        }

        HealthWatcher watcher = new HealthWatcher(fresh, this);
        fresh.setHealthCancel(watcher::stop);
        watcher.start();
        events.emit(RegistryEvent.serverAdded(ServerView.of(fresh)));
        LOG.info("Registered {} @ {}:{}", id, host, port);
        return fresh;
    }

    /** Called from {@link HealthWatcher#onNext}. */
    public void markStatus(ServerEntry entry, ServerStatus status) {
        entry.touch();
        if (entry.setStatus(status)) {
            events.emit(RegistryEvent.statusChanged(entry.id(), status.name()));
        }
    }

    /**
     * Called from {@link HealthWatcher#onError}/{@link HealthWatcher#onCompleted}. Only
     * removes the entry if the registry still holds the SAME instance the watcher was
     * watching. Also reports the death to the broker so peer BFFs can converge.
     */
    public void healthFailed(ServerEntry watched) {
        boolean[] removed = { false };
        entries.compute(watched.id(), (k, current) -> {
            if (current == watched) {
                removed[0] = true;
                return null;
            }
            return current; // entry has been replaced — don't touch it
        });

        watched.channel().shutdownNow();

        if (removed[0]) {
            watched.setStatus(ServerStatus.DEAD);
            events.emit(RegistryEvent.statusChanged(watched.id(), ServerStatus.DEAD.name()));
            events.emit(RegistryEvent.serverRemoved(watched.id()));
            LOG.info("Marked DEAD + removed {}", watched.id());
            // Tell the broker so other BFFs converge quickly. Best-effort.
            if (!brokerTarget.isBlank()) {
                BrokerAnnouncer.withdrawNode(brokerTarget, watched.id());
            }
        } else {
            LOG.info("Stale health failure for {} ignored — entry already replaced", watched.id());
        }
    }

    /** Called from BrokerSubscriber on LEFT events. */
    public void removeById(String id) {
        ServerEntry e = entries.remove(id);
        if (e != null) {
            e.cancelHealth();
            e.channel().shutdownNow();
            events.emit(RegistryEvent.serverRemoved(id));
            LOG.info("Removed {} (broker LEFT)", id);
        }
    }

    public Collection<ServerEntry> snapshot() {
        return entries.values();
    }

    public Optional<ServerEntry> get(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @PreDestroy
    void shutdown() {
        entries.values().forEach(e -> {
            e.cancelHealth();
            e.channel().shutdownNow();
        });
        entries.clear();
    }
}
