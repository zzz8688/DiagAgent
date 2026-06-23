package io.github.zzz8688.diagagent.store;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TopologyService {

    private final Map<String, List<String>> downstream = Map.of(
            "frontend", List.of("order-service"),
            "order-service", List.of("product-service", "payment-service"),
            "product-service", List.of("inventory-db"),
            "payment-service", List.of("external-payment-gateway"),
            "inventory-db", List.of(),
            "external-payment-gateway", List.of()
    );

    public String getDownstreamDependencies(String serviceName) {
        return downstream.getOrDefault(serviceName, List.of()).stream().collect(Collectors.joining("\n"));
    }

    public String getUpstreamDependents(String serviceName) {
        return downstream.entrySet().stream()
                .filter(entry -> entry.getValue().contains(serviceName))
                .map(Map.Entry::getKey)
                .collect(Collectors.joining("\n"));
    }
}
