package com.example.poc.bff.registry;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Owns the BFF's own gRPC server (hosts the RegistryService). Eagerly initialised via @Context.
 */
@Context
public final class GrpcServerLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final int port;
    private final RegistryServiceImpl registryService;
    private Server server;

    public GrpcServerLifecycle(@Value("${poc.bff.grpcPort:7000}") int port,
                               RegistryServiceImpl registryService) {
        this.port = port;
        this.registryService = registryService;
    }

    @PostConstruct
    void start() throws Exception {
        this.server = NettyServerBuilder.forPort(port)
                .permitKeepAliveTime(1, TimeUnit.SECONDS)
                .permitKeepAliveWithoutCalls(true)
                .addService(registryService)
                .build()
                .start();
        LOG.info("BFF gRPC server listening on :{}", port);
    }

    @PreDestroy
    void stop() {
        if (server != null) {
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
