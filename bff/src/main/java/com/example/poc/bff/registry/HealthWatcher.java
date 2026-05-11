package com.example.poc.bff.registry;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds an open {@code grpc.health.v1.Health/Watch} stream to one backend and reports
 * status transitions back to the {@link ServerRegistry}. The watcher carries the
 * {@link ServerEntry} it was created for so that on failure the registry can ignore
 * stale errors from entries that have already been replaced.
 */
final class HealthWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(HealthWatcher.class);

    private final ServerEntry entry;
    private final ServerRegistry registry;
    private volatile Context.CancellableContext context;
    private volatile boolean cancelled;

    HealthWatcher(ServerEntry entry, ServerRegistry registry) {
        this.entry = entry;
        this.registry = registry;
    }

    void start() {
        HealthGrpc.HealthStub stub = HealthGrpc.newStub(entry.channel());
        HealthCheckRequest req = HealthCheckRequest.newBuilder().setService("").build();
        StreamObserver<HealthCheckResponse> observer = new StreamObserver<>() {
            @Override
            public void onNext(HealthCheckResponse resp) {
                if (cancelled) return;
                registry.markStatus(entry, map(resp.getStatus()));
            }

            @Override
            public void onError(Throwable t) {
                if (cancelled) return;
                LOG.info("Health watch for {} errored ({})", entry.id(), t.getMessage());
                registry.healthFailed(entry);
            }

            @Override
            public void onCompleted() {
                if (cancelled) return;
                LOG.info("Health watch for {} ended cleanly", entry.id());
                registry.healthFailed(entry);
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
