package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.store.TopologyService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Slf4j
public class SystemController {

    private final TopologyService topologyService;
    private final Random random = new Random();

    @GetMapping("/topology")
    public TopologyData getTopology() {
        log.info("获取系统拓扑图");

        List<TopologyNode> nodes = new ArrayList<>();
        List<TopologyLink> links = new ArrayList<>();

        Map<String, String> serviceStatus = getServiceStatus();

        nodes.add(new TopologyNode("frontend", "Frontend", serviceStatus.get("frontend"), 400, 100));
        nodes.add(new TopologyNode("order-service", "Order Service", serviceStatus.get("order-service"), 400, 250));
        nodes.add(new TopologyNode("product-service", "Product Service", serviceStatus.get("product-service"), 200, 400));
        nodes.add(new TopologyNode("payment-service", "Payment Service", serviceStatus.get("payment-service"), 600, 400));
        nodes.add(new TopologyNode("inventory-db", "Inventory DB", serviceStatus.get("inventory-db"), 100, 550));
        nodes.add(new TopologyNode("external-payment-gateway", "Payment Gateway", serviceStatus.get("external-payment-gateway"), 700, 550));

        links.add(new TopologyLink("frontend", "order-service"));
        links.add(new TopologyLink("order-service", "product-service"));
        links.add(new TopologyLink("order-service", "payment-service"));
        links.add(new TopologyLink("product-service", "inventory-db"));
        links.add(new TopologyLink("payment-service", "external-payment-gateway"));

        return new TopologyData(nodes, links);
    }

    @GetMapping("/health")
    public List<ServiceHealth> getHealthStatus() {
        log.info("获取服务健康状态");

        List<ServiceHealth> healthList = new ArrayList<>();

        healthList.add(createServiceHealth("frontend", "Frontend", "OK", random.nextInt(30) + 10, random.nextInt(40) + 20, random.nextInt(50) + 10, 0));
        healthList.add(createServiceHealth("order-service", "Order Service", "OK", random.nextInt(30) + 10, random.nextInt(40) + 20, random.nextInt(50) + 10, 0));
        healthList.add(createServiceHealth("product-service", "Product Service", "Warning", 65, 55, 2500, 15));
        healthList.add(createServiceHealth("payment-service", "Payment Service", "Warning", 45, 40, 3155, 8));
        healthList.add(createServiceHealth("inventory-db", "Inventory DB", "Critical", 95, 78, 2500, 45));
        healthList.add(createServiceHealth("external-payment-gateway", "Payment Gateway", "Warning", 0, 0, 3000, 100));

        return healthList;
    }

    private Map<String, String> getServiceStatus() {
        Map<String, String> status = new HashMap<>();
        status.put("frontend", "OK");
        status.put("order-service", "OK");
        status.put("product-service", "Warning");
        status.put("payment-service", "Warning");
        status.put("inventory-db", "Critical");
        status.put("external-payment-gateway", "Warning");
        return status;
    }

    private ServiceHealth createServiceHealth(String id, String name, String status, int cpu, int memory, int latency, int errorRate) {
        return new ServiceHealth(id, name, status, cpu, memory, latency, errorRate);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopologyData {
        private List<TopologyNode> nodes;
        private List<TopologyLink> links;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopologyNode {
        private String id;
        private String name;
        private String status;
        private int x;
        private int y;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopologyLink {
        private String source;
        private String target;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceHealth {
        private String id;
        private String name;
        private String status;
        private int cpu;
        private int memory;
        private int latency;
        private int errorRate;
    }
}
