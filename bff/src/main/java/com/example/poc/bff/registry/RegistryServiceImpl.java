package com.example.poc.bff.registry;

import com.example.poc.registry.RegisterRequest;
import com.example.poc.registry.RegisterResponse;
import com.example.poc.registry.RegistryServiceGrpc;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public final class RegistryServiceImpl extends RegistryServiceGrpc.RegistryServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(RegistryServiceImpl.class);

    private final ServerRegistry registry;

    public RegistryServiceImpl(ServerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void registerServer(RegisterRequest req, StreamObserver<RegisterResponse> resp) {
        LOG.info("RegisterServer {} @ {}:{}", req.getServerId(), req.getHost(), req.getPort());
        registry.register(req.getServerId(), req.getHost(), req.getPort());
        resp.onNext(RegisterResponse.newBuilder()
                .setAccepted(true)
                .setMessage("registered as " + req.getServerId())
                .build());
        resp.onCompleted();
    }
}
