package com.example.poc.server;

import com.example.poc.echo.ComputeRequest;
import com.example.poc.echo.ComputeResponse;
import com.example.poc.echo.EchoRequest;
import com.example.poc.echo.EchoResponse;
import com.example.poc.echo.EchoServiceGrpc;
import com.example.poc.echo.Empty;
import com.example.poc.echo.ServerInfo;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

public final class EchoServiceImpl extends EchoServiceGrpc.EchoServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(EchoServiceImpl.class);

    private final String serverId;
    private final long startedAtMs = Instant.now().toEpochMilli();
    private final AtomicLong handled = new AtomicLong();

    public EchoServiceImpl(String serverId) {
        this.serverId = serverId;
    }

    @Override
    public void echo(EchoRequest req, StreamObserver<EchoResponse> resp) {
        handled.incrementAndGet();
        LOG.debug("echo seq={} msg={}", req.getClientSeq(), req.getMessage());
        resp.onNext(EchoResponse.newBuilder()
                .setMessage(req.getMessage())
                .setServerId(serverId)
                .setHandledAtMs(Instant.now().toEpochMilli())
                .build());
        resp.onCompleted();
    }

    @Override
    public void compute(ComputeRequest req, StreamObserver<ComputeResponse> resp) {
        long workMs = Math.max(1, req.getWorkMs());
        long started = System.nanoTime();
        try {
            Thread.sleep(workMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        handled.incrementAndGet();
        resp.onNext(ComputeResponse.newBuilder()
                .setServerId(serverId)
                .setElapsedMs((System.nanoTime() - started) / 1_000_000)
                .build());
        resp.onCompleted();
    }

    @Override
    public void getServerInfo(Empty req, StreamObserver<ServerInfo> resp) {
        resp.onNext(ServerInfo.newBuilder()
                .setServerId(serverId)
                .setStartedAtMs(startedAtMs)
                .setHandledTotal(handled.get())
                .build());
        resp.onCompleted();
    }
}
