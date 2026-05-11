package com.example.poc.broker;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public final class BrokerApp {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerApp.class);

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(envOrDefault("BROKER_PORT", "7100"));
        Server server = NettyServerBuilder.forPort(port)
                .permitKeepAliveTime(1, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true)
                .addService(new LifecycleBrokerServiceImpl())
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
