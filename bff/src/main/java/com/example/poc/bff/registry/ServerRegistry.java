package com.example.poc.bff.registry;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
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

    public ServerRegistry(EventBus events) {
        this.events = events;
    }

    /**
     * Register (or RE-register) a backend. A second RegisterServer call for the same id
     * means a NEW physical instance (e.g. container restart): the old entry's channel
     * almost certainly points at a dead peer with a stale IP. So we always replace and
     * rebuild the channel + watcher.
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

        // Tear down the prior entry's resources outside the map's compute lock.
        if (previous[0] != null) {
            LOG.info("Replacing stale entry for {} (was {}:{})", id, previous[0].host(), previous[0].port());
            previous[0].cancelHealth();
            previous[0].channel().shutdownNow();
            // No serverRemoved event — we go straight from old-card to new-card via serverAdded below.
        }

        HealthWatcher watcher = new HealthWatcher(fresh, this);
        fresh.setHealthCancel(watcher::stop);
        watcher.start();
        events.emit(RegistryEvent.serverAdded(ServerView.of(fresh)));
        LOG.info("Registered {} @ {}:{}", id, host, port);
        return fresh;
    }

    /** Called from {@link HealthWatcher#onNext}. Touch + update status on the watcher's own entry. */
    public void markStatus(ServerEntry entry, ServerStatus status) {
        entry.touch();
        if (entry.setStatus(status)) {
            events.emit(RegistryEvent.statusChanged(entry.id(), status.name()));
        }
    }

    /**
     * Called from {@link HealthWatcher#onError}/{@link HealthWatcher#onCompleted}. Only
     * removes the entry if the registry still holds the SAME instance the watcher was
     * watching — otherwise the entry has already been replaced by a newer registration
     * and we must not touch it.
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

        // Always clean up the obsolete channel; harmless if already shut down.
        watched.channel().shutdownNow();

        if (removed[0]) {
            watched.setStatus(ServerStatus.DEAD);
            events.emit(RegistryEvent.statusChanged(watched.id(), ServerStatus.DEAD.name()));
            events.emit(RegistryEvent.serverRemoved(watched.id()));
            LOG.info("Marked DEAD + removed {}", watched.id());
        } else {
            LOG.info("Stale health failure for {} ignored — entry already replaced", watched.id());
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
