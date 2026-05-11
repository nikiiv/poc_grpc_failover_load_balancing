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

    public ServerEntry register(String id, String host, int port) {
        boolean[] created = { false };
        ServerEntry entry = entries.computeIfAbsent(id, k -> {
            ManagedChannel ch = NettyChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .keepAliveTime(2, TimeUnit.SECONDS)
                    .keepAliveTimeout(1, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .build();
            LOG.info("Registered {} @ {}:{}", id, host, port);
            created[0] = true;
            return new ServerEntry(id, host, port, ch);
        });
        if (created[0]) {
            HealthWatcher watcher = new HealthWatcher(id, entry.channel(), this);
            entry.setHealthCancel(watcher::stop);
            watcher.start();
            events.emit(RegistryEvent.serverAdded(ServerView.of(entry)));
        }
        return entry;
    }

    public void remove(String id) {
        ServerEntry e = entries.remove(id);
        if (e != null) {
            e.cancelHealth();
            e.channel().shutdownNow();
            events.emit(RegistryEvent.serverRemoved(id));
        }
    }

    /** Health stream said NOT_SERVING / UNHEALTHY / SERVING — update + emit if changed. */
    public void touchAndSetStatus(String id, ServerStatus status) {
        ServerEntry e = entries.get(id);
        if (e == null) return;
        e.touch();
        if (e.setStatus(status)) {
            events.emit(RegistryEvent.statusChanged(id, status.name()));
        }
    }

    /** Health stream broke or completed: backend is gone. Flash DEAD then remove. */
    public void markDeadAndRemove(String id) {
        ServerEntry e = entries.get(id);
        if (e == null) return;
        if (e.setStatus(ServerStatus.DEAD)) {
            events.emit(RegistryEvent.statusChanged(id, ServerStatus.DEAD.name()));
        }
        e.cancelHealth();
        entries.remove(id);
        e.channel().shutdownNow();
        events.emit(RegistryEvent.serverRemoved(id));
        LOG.info("Marked DEAD + removed {}", id);
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
