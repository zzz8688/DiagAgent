package io.github.zzz8688.diagagent.tools;

import io.github.zzz8688.diagagent.store.TopologyService;
import dev.langchain4j.agent.tool.Tool;
import io.github.zzz8688.diagagent.tools.registry.PlatformTool;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TopologyQueryTool extends BaseTool {

    private static final Logger log = LoggerFactory.getLogger(TopologyQueryTool.class);

    private final TopologyService topologyService;

    @PlatformTool(description = "查询服务依赖图以查找下游服务（此服务依赖的服务）或上游服务（依赖此服务的服务）。用于追踪错误。")
    @Tool("查询服务依赖图以查找下游服务（此服务依赖的服务）或上游服务（依赖此服务的服务）。用于追踪错误。")
    public String queryTopology(String serviceName, String direction) {
        log.info("Agent 正在查询拓扑: {} ({})", serviceName, direction);
        beforeToolCall("queryTopology", serviceName + "|" + direction);
        try {
            checkCancelled();

            if ("downstream".equalsIgnoreCase(direction) || "下游".equals(direction)) {
                checkCancelled();
                String deps = topologyService.getDownstreamDependencies(serviceName);
                return serviceName + " 依赖于:\n" + deps;
            } else if ("upstream".equalsIgnoreCase(direction) || "上游".equals(direction)) {
                checkCancelled();
                String deps = topologyService.getUpstreamDependents(serviceName);
                return serviceName + " 被以下服务调用:\n" + deps;
            } else {
                return "无效的方向。请使用 'upstream' (上游) 或 'downstream' (下游)。";
            }
        } catch (BaseTool.ToolExecutionCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询拓扑失败", e);
            return "查询拓扑失败: " + e.getMessage();
        }
    }

    @PlatformTool(description = "获取指定服务的所有依赖信息，包括下游依赖及其状态。")
    @Tool("获取指定服务的所有依赖信息，包括下游依赖及其状态。")
    public String getDependencies(String serviceName) {
        log.info("Agent 正在获取服务依赖: {}", serviceName);
        beforeToolCall("getDependencies", serviceName);
        try {
            checkCancelled();
            return topologyService.getDownstreamDependencies(serviceName);
        } catch (BaseTool.ToolExecutionCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取服务依赖失败", e);
            return "获取服务依赖失败: " + e.getMessage();
        }
    }
}
