package io.github.zzz8688.diagagent.tools.registry;

import java.util.List;

public interface ToolRegistry {

    List<RegisteredTool> listTools();

    List<RegisteredTool> listVisibleTools();

    RegisteredTool findTool(String toolName);
}
