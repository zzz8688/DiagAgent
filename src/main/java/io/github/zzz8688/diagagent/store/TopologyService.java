package io.github.zzz8688.diagagent.store;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TopologyService {

    private final Map<String, List<String>> dependencies = new HashMap<>();
    private final Map<String, List<String>> reverseDependencies = new HashMap<>();

    public TopologyService() {
        addEdge("frontend", "order-service");
        addEdge("order-service", "product-service");
        addEdge("order-service", "payment-service");
        addEdge("product-service", "inventory-db");
        addEdge("payment-service", "external-payment-gateway");
    }

    private void addEdge(String from, String to) {
        dependencies.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        reverseDependencies.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
    }

    public String getDownstreamDependencies(String serviceName) {
        List<String> deps = dependencies.getOrDefault(serviceName, Collections.emptyList());
        if (deps.isEmpty()) {
            return serviceName + " has no downstream dependencies.";
        }
        return formatDependencies(deps, serviceName);
    }

    public String getUpstreamDependents(String serviceName) {
        List<String> deps = reverseDependencies.getOrDefault(serviceName, Collections.emptyList());
        if (deps.isEmpty()) {
            return serviceName + " is not called by any known services.";
        }
        return formatDependencies(deps, serviceName);
    }

    private String formatDependencies(List<String> services, String queryService) {
        return services.stream()
                .map(service -> {
                    if ("inventory-db".equals(service) && "product-service".equals(queryService)) {
                        return String.format("- %s (Status: Critical, Error Rate: 45%%, Latency: 2500ms)", service);
                    } else if ("external-payment-gateway".equals(service)) {
                        return String.format("- %s (Status: Warning, Error Rate: 8%%, Latency: 1200ms)", service);
                    } else {
                        return String.format("- %s (Status: OK, Error Rate: 0%%, Latency: 150ms)", service);
                    }
                })
                .collect(Collectors.joining("\n"));
    }
}
