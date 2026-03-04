package io.github.zzz8688.diagagent.tools;

import io.github.zzz8688.diagagent.store.TopologyService;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class TopologyQueryTool {

    private final TopologyService topologyService;

    @Tool("查询服务依赖图以查找下游服务（此服务依赖的服务）或上游服务（依赖此服务的服务）。用于追踪错误。")
    public String queryTopology(String serviceName, String direction) {
        log.info("Agent 正在查询拓扑: {} ({})", serviceName, direction);

        if ("downstream".equalsIgnoreCase(direction) || "下游".equals(direction)) {
            String deps = topologyService.getDownstreamDependencies(serviceName);
            return serviceName + " 依赖于:\n" + deps;
        } else if ("upstream".equalsIgnoreCase(direction) || "上游".equals(direction)) {
            String deps = topologyService.getUpstreamDependents(serviceName);
            return serviceName + " 被以下服务调用:\n" + deps;
        } else {
            return "无效的方向。请使用 'upstream' (上游) 或 'downstream' (下游)。";
        }
    }

    @Tool("获取指定服务的所有依赖信息，包括下游依赖及其状态。")
    public String getDependencies(String serviceName) {
        log.info("Agent 正在获取服务依赖: {}", serviceName);
        return topologyService.getDownstreamDependencies(serviceName);
    }
}
