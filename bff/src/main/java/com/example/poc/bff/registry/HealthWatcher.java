package com.example.poc.bff.registry;

import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds an open {@code grpc.health.v1.Health/Watch} stream to one backend and reports
 * status transitions back to the {@link ServerRegistry}. {@link #stop()} cancels the
 * stream cleanly so we can distinguish "we hung up" from "the backend died".
 */
final class HealthWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(HealthWatcher.class);

    private final String serverId;
    private final ManagedChannel channel;
    private final ServerRegistry registry;
    private volatile Context.CancellableContext context;
    private volatile boolean cancelled;

    HealthWatcher(String serverId, ManagedChannel channel, ServerRegistry registry) {
        this.serverId = serverId;
        this.channel = channel;
        this.registry = registry;
    }

    void start() {
        HealthGrpc.HealthStub stub = HealthGrpc.newStub(channel);
        HealthCheckRequest req = HealthCheckRequest.newBuilder().setService("").build();
        StreamObserver<HealthCheckResponse> observer = new StreamObserver<>() {
            @Override
            public void onNext(HealthCheckResponse resp) {
                if (cancelled) return;
                registry.touchAndSetStatus(serverId, map(resp.getStatus()));
            }

            @Override
            public void onError(Throwable t) {
                if (cancelled) return;
                LOG.info("Health watch for {} errored ({}) — marking DEAD", serverId, t.getMessage());
                registry.markDeadAndRemove(serverId);
            }

            @Override
            public void onCompleted() {
                if (cancelled) return;
                LOG.info("Health watch for {} ended cleanly — marking DEAD", serverId);
                registry.markDeadAndRemove(serverId);
            }
        };

        // Detach from any inbound-RPC context (e.g. the RegisterServer call). Without this,
        // the watcher's context becomes a child of the inbound call's context and is cancelled
        // the moment that call completes.
        Context.CancellableContext ctx = Context.ROOT.withCancellation();
        this.context = ctx;
        ctx.run(() -> stub.watch(req, observer));
    }

    void stop() {
        cancelled = true;
        Context.CancellableContext ctx = context;
        if (ctx != null) {
            ctx.cancel(Status.CANCELLED.withDescription("watcher stopped").asRuntimeException());
        }
    }

    private static ServerStatus map(HealthCheckResponse.ServingStatus s) {
        return switch (s) {
            case SERVING -> ServerStatus.HEALTHY;
            case NOT_SERVING -> ServerStatus.DRAINING;
            case UNKNOWN, SERVICE_UNKNOWN, UNRECOGNIZED -> ServerStatus.UNHEALTHY;
        };
    }
}
