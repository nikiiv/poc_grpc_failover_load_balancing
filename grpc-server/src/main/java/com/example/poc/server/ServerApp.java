package com.example.poc.server;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ServerApp {

    private static final Logger LOG = LoggerFactory.getLogger(ServerApp.class);

    public static void main(String[] args) throws Exception {
        String serverId = envOrDefault("SERVER_ID", "server-local");
        String role = envOrDefault("ROLE", "trading");
        int port = Integer.parseInt(envOrDefault("SERVER_PORT", "9101"));
        String advertisedHost = envOrDefault("ADVERTISED_HOST", defaultHostname());
        String brokerTarget = envOrDefault("BROKER_TARGET", "");

        HealthStatusManager health = new HealthStatusManager();
        AtomicReference<Server> serverRef = new AtomicReference<>();
        DrainServiceImpl drainService = new DrainServiceImpl(health, serverRef);

        Server server = NettyServerBuilder.forPort(port)
                .permitKeepAliveTime(1, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true)
                .addService(new EchoServiceImpl(serverId))
                .addService(health.getHealthService())
                .addService(drainService)
                .build()
                .start();
        serverRef.set(server);

        health.setStatus("", io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING);

        LOG.info("Backend '{}' (role={}) listening on :{} (advertising {}:{})",
                serverId, role, port, advertisedHost, port);

        BrokerAnnouncer announcer = null;
        if (!brokerTarget.isBlank()) {
            announcer = new BrokerAnnouncer(brokerTarget, role, serverId, advertisedHost + ":" + port);
            BrokerAnnouncer finalAnnouncer = announcer;
            Thread t = new Thread(finalAnnouncer::announceWithRetry, "broker-announcer");
            t.setDaemon(true);
            t.start();
        } else {
            LOG.info("BROKER_TARGET not set — skipping announcement");
        }

        BrokerAnnouncer finalAnnouncer = announcer;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("SIGTERM received — shutting down '{}'", serverId);
            health.setStatus("", io.grpc.health.v1.HealthCheckResponse.ServingStatus.NOT_SERVING);
            if (finalAnnouncer != null) {
                finalAnnouncer.withdraw();
            }
            server.shutdown();
            try {
                server.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "grpc-shutdown"));

        server.awaitTermination();
    }

    private static String defaultHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private static String envOrDefault(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private ServerApp() {}
}
