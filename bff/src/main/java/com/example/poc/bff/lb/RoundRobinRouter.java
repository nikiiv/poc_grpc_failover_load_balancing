package com.example.poc.bff.lb;

import com.example.poc.bff.registry.ServerEntry;
import com.example.poc.bff.registry.ServerRegistry;
import com.example.poc.bff.registry.ServerStatus;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Singleton
public final class RoundRobinRouter {

    private final ServerRegistry registry;
    private final AtomicInteger cursor = new AtomicInteger();

    public RoundRobinRouter(ServerRegistry registry) {
        this.registry = registry;
    }

    public Optional<ServerEntry> pickHealthy() {
        List<ServerEntry> healthy = registry.snapshot().stream()
                .filter(e -> e.status() == ServerStatus.HEALTHY)
                .toList();
        if (healthy.isEmpty()) {
            return Optional.empty();
        }
        int idx = Math.floorMod(cursor.getAndIncrement(), healthy.size());
        return Optional.of(healthy.get(idx));
    }
}
