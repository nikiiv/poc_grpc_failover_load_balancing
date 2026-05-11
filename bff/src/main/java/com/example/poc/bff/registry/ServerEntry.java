package com.example.poc.bff.registry;

import com.example.poc.echo.EchoServiceGrpc;
import io.grpc.ManagedChannel;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable bookkeeping for one registered backend. Concurrent access is fine for the
 * counters and the volatile status; channel/stub are immutable post-construction.
 */
public final class ServerEntry {

    private final String id;
    private final String host;
    private final int port;
    private final Instant registeredAt;
    private final ManagedChannel channel;
    private final EchoServiceGrpc.EchoServiceBlockingStub echoStub;

    private final AtomicReference<ServerStatus> status = new AtomicReference<>(ServerStatus.HEALTHY);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong totalHandled = new AtomicLong();
    private volatile Instant lastSeen = Instant.now();
    private volatile Runnable healthCancel;

    public ServerEntry(String id, String host, int port, ManagedChannel channel) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.registeredAt = Instant.now();
        this.channel = channel;
        this.echoStub = EchoServiceGrpc.newBlockingStub(channel);
    }

    public String id() { return id; }
    public String host() { return host; }
    public int port() { return port; }
    public Instant registeredAt() { return registeredAt; }
    public ManagedChannel channel() { return channel; }
    public EchoServiceGrpc.EchoServiceBlockingStub echoStub() { return echoStub; }

    public ServerStatus status() { return status.get(); }
    public boolean setStatus(ServerStatus next) {
        ServerStatus prev = status.getAndSet(next);
        return prev != next;
    }

    public int inFlight() { return inFlight.get(); }
    public int incrementInFlight() { return inFlight.incrementAndGet(); }
    public int decrementInFlight() { return inFlight.decrementAndGet(); }

    public long totalHandled() { return totalHandled.get(); }
    public long incrementHandled() { return totalHandled.incrementAndGet(); }

    public Instant lastSeen() { return lastSeen; }
    public void touch() { this.lastSeen = Instant.now(); }

    public void setHealthCancel(Runnable r) { this.healthCancel = r; }
    public void cancelHealth() {
        Runnable r = this.healthCancel;
        if (r != null) {
            try { r.run(); } catch (Exception ignored) { }
        }
    }
}
