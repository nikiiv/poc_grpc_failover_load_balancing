package com.example.poc.bff;

import com.example.poc.bff.registry.KnownNode;
import com.example.poc.bff.registry.KnownNodesRegistry;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.Comparator;
import java.util.List;

@Controller("/api/known-nodes")
public final class KnownNodesController {

    private final KnownNodesRegistry knownNodes;

    public KnownNodesController(KnownNodesRegistry knownNodes) {
        this.knownNodes = knownNodes;
    }

    @Get
    public List<KnownNode> list() {
        return knownNodes.snapshot().stream()
                .sorted(Comparator.comparing(KnownNode::role).thenComparing(KnownNode::nodeId))
                .toList();
    }
}
