package com.example.poc.broker;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

public final class BrokerApp {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerApp.class);

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(envOrDefault("BROKER_PORT", "7100"));
        String consulTarget = envOrDefault("CONSUL_TARGET", "");

        LifecycleBrokerServiceImpl service = new LifecycleBrokerServiceImpl();

        // Bootstrap from Consul BEFORE starting the gRPC listener — guarantees that any
        // Subscribe call that arrives immediately after restart sees a populated snapshot
        // rather than an empty one.
        if (!consulTarget.isBlank()) {
            LOG.info("Bootstrapping from Consul at {}...", consulTarget);
            List<com.example.poc.lifecycle.NodeInfo> initial =
                    new ConsulBootstrap(consulTarget).seed();
            service.seed(initial);
        } else {
            LOG.info("CONSUL_TARGET not set — starting with empty cluster view");
        }

        Server server = NettyServerBuilder.forPort(port)
                .permitKeepAliveTime(1, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true)
                .addService(service)
                .build()
                .start();

        LOG.info("LifecycleBroker listening on :{}", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down broker");
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "broker-shutdown"));

        server.awaitTermination();
    }

    private static String envOrDefault(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private BrokerApp() {}
}
