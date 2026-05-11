package com.example.poc.server;

import com.example.poc.registry.DrainRequest;
import com.example.poc.registry.DrainResponse;
import com.example.poc.registry.DrainServiceGrpc;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drain flow:
 *  1. Flip health to NOT_SERVING — BFF's HealthWatcher sees this via the Watch stream and
 *     flips the UI card to DRAINING. The BFF's RoundRobinRouter stops picking this server.
 *  2. Reply to the caller (the drain RPC itself).
 *  3. Off-thread: call {@code server.shutdown()} to refuse new RPCs, sleep for a brief
 *     grace period so any in-flight unary calls finish, then {@code shutdownNow()} which
 *     closes the BFF's long-lived Watch stream. The BFF then sees {@code onError} and
 *     removes the server.
 *
 * <p>Why not {@code awaitTermination} instead of a fixed sleep? The Health.Watch stream
 * counts as in-flight to grpc-java, so {@code awaitTermination} blocks for the full deadline
 * even when no real work is in progress. A fixed grace + {@code shutdownNow} is simpler and
 * gives the demo a predictable visual cadence.
 */
final class DrainServiceImpl extends DrainServiceGrpc.DrainServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(DrainServiceImpl.class);

    private final HealthStatusManager health;
    private final AtomicReference<Server> serverRef;

    DrainServiceImpl(HealthStatusManager health, AtomicReference<Server> serverRef) {
        this.health = health;
        this.serverRef = serverRef;
    }

    @Override
    public void requestDrain(DrainRequest req, StreamObserver<DrainResponse> resp) {
        int graceSeconds = req.getDeadlineSeconds() <= 0 ? 3 : req.getDeadlineSeconds();
        LOG.info("Drain requested, grace {}s", graceSeconds);

        health.setStatus("", HealthCheckResponse.ServingStatus.NOT_SERVING);

        resp.onNext(DrainResponse.newBuilder()
                .setAccepted(true)
                .setMessage("draining with grace " + graceSeconds + "s")
                .build());
        resp.onCompleted();

        Thread drainer = new Thread(() -> {
            Server server = serverRef.get();
            if (server == null) {
                LOG.error("No server reference — exiting immediately");
                System.exit(1);
                return;
            }
            server.shutdown(); // refuse new RPCs; in-flight unary keeps running
            try {
                Thread.sleep(graceSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            server.shutdownNow(); // close everything, including the BFF's Watch stream
            try {
                server.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            LOG.info("Drain complete — exiting");
            System.exit(0);
        }, "drainer");
        drainer.setDaemon(false);
        drainer.start();
    }
}
