package io.github.zzz8688.diagagent.app.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zzz8688.diagagent.a2a.A2aBootstrapService;
import io.github.zzz8688.diagagent.a2a.A2aClientAdapter;
import io.github.zzz8688.diagagent.a2a.A2aRegistryService;
import io.github.zzz8688.diagagent.a2a.A2aSendMessageParams;
import io.github.zzz8688.diagagent.a2a.A2aTaskIdParams;
import io.github.zzz8688.diagagent.a2a.A2aTaskQueryParams;
import io.github.zzz8688.diagagent.mcp.McpRegistryService;
import io.github.zzz8688.diagagent.mcp.McpBootstrapService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DiagAgentProtocolCli {

    private DiagAgentProtocolCli() {
    }

    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DiagAgentProtocolCliApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
                        "spring.cloud.nacos.discovery.enabled=false",
                        "spring.cloud.nacos.config.enabled=false"
                )
                .run(args)) {
            context.getBean(McpBootstrapService.class);
            context.getBean(A2aBootstrapService.class);
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            McpRegistryService mcpRegistryService = context.getBean(McpRegistryService.class);
            A2aRegistryService a2aRegistryService = context.getBean(A2aRegistryService.class);
            A2aClientAdapter a2aClientAdapter = context.getBean(A2aClientAdapter.class);
            try {
                handleCommand(args, objectMapper, mcpRegistryService, a2aRegistryService, a2aClientAdapter);
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.err.println(e.getMessage());
                System.exit(1);
            }
        }
    }

    private static void handleCommand(String[] args,
                                      ObjectMapper objectMapper,
                                      McpRegistryService mcpRegistryService,
                                      A2aRegistryService a2aRegistryService,
                                      A2aClientAdapter a2aClientAdapter) throws Exception {
        String command = args != null && args.length > 0 ? args[0] : "registry";
        switch (command.toLowerCase()) {
            case "registry" -> printJson(objectMapper, new ProtocolCliSnapshot(
                    mcpRegistryService.snapshot(),
                    mcpRegistryService.cliState(),
                    a2aRegistryService.snapshot(),
                    a2aRegistryService.cliState()
            ));
            case "mcp" -> printJson(objectMapper, mcpRegistryService.snapshot());
            case "mcp-state" -> printJson(objectMapper, mcpRegistryService.cliState());
            case "a2a" -> printJson(objectMapper, a2aRegistryService.snapshot());
            case "a2a-state" -> printJson(objectMapper, a2aRegistryService.cliState());
            case "agent-card" -> printJson(objectMapper, a2aRegistryService.selfAgentCard().orElse(null));
            case "a2a-card" -> printJson(objectMapper, new A2aCardSnapshot(
                    requirePositional(command, args, 0),
                    a2aClientAdapter.fetchAgentCard(requirePositional(command, args, 0))
            ));
            case "a2a-submit" -> printJson(objectMapper, a2aClientAdapter.sendMessage(
                    requirePositional(command, args, 0),
                    buildSendMessageParams(args, command)
            ));
            case "a2a-get-task" -> printJson(objectMapper, a2aClientAdapter.getTask(
                    requirePositional(command, args, 0),
                    new A2aTaskQueryParams(
                            requirePositional(command, args, 1),
                            parseIntegerOption(args, "--history-length"),
                            null
                    )
            ));
            case "a2a-cancel-task" -> printJson(objectMapper, a2aClientAdapter.cancelTask(
                    requirePositional(command, args, 0),
                    new A2aTaskIdParams(
                            requirePositional(command, args, 1),
                            null
                    )
            ));
            case "help", "--help", "-h" -> printUsage();
            default -> {
                System.err.println("未知命令: " + command);
                printUsage();
                System.exit(1);
            }
        }
    }

    private static A2aSendMessageParams buildSendMessageParams(String[] args, String command) {
        List<String> positional = positionalArgs(args);
        if (positional.size() < 2) {
            throw new IllegalArgumentException("命令 `" + command + "` 缺少参数，至少需要 `<agentId> <message>`");
        }
        String messageText = String.join(" ", positional.subList(1, positional.size())).trim();
        if (messageText.isBlank()) {
            throw new IllegalArgumentException("命令 `" + command + "` 的 message 不能为空");
        }
        return new A2aSendMessageParams(
                new A2aSendMessageParams.Message(
                        UUID.randomUUID().toString(),
                        defaultText(readOption(args, "--context"), null),
                        defaultText(readOption(args, "--task"), null),
                        A2aSendMessageParams.Role.ROLE_USER,
                        List.of(A2aSendMessageParams.Part.text(messageText)),
                        Map.of("source", "protocol-cli"),
                        null,
                        parseCsvOption(args, "--reference")
                ),
                new A2aSendMessageParams.Configuration(List.of("text"), 0, null, false),
                Map.of("source", "protocol-cli")
        );
    }

    private static void printJson(ObjectMapper objectMapper, Object payload) throws Exception {
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
    }

    private static void printUsage() {
        System.out.println("""
                用法:
                  java -cp <app> io.github.zzz8688.diagagent.app.cli.DiagAgentProtocolCli [command]

                可用命令:
                  registry    输出 MCP + A2A 总览
                  mcp         输出 MCP registry 快照
                  mcp-state   输出 MCP CLI state 快照
                  a2a         输出 A2A registry 快照
                  a2a-state   输出 A2A CLI state 快照
                  agent-card  输出 DiagAgent 自身 A2A Agent Card
                  a2a-card <agentId>
                             拉取远端 A2A Agent Card 并刷新当前进程内 registry 状态
                  a2a-submit <agentId> <message> [--context <contextId>] [--task <taskId>] [--reference <taskId1,taskId2>]
                             向远端 A2A agent 发送 SendMessage
                  a2a-get-task <agentId> <taskId> [--history-length <n>]
                             查询远端 A2A task 当前状态
                  a2a-cancel-task <agentId> <taskId> [--reason <text>]
                             取消远端 A2A task
                """);
    }

    private static String requirePositional(String command, String[] args, int index) {
        List<String> positional = positionalArgs(args);
        if (index < positional.size()) {
            return positional.get(index);
        }
        throw new IllegalArgumentException("命令 `" + command + "` 缺少位置参数");
    }

    private static List<String> positionalArgs(String[] args) {
        List<String> positional = new ArrayList<>();
        if (args == null || args.length <= 1) {
            return positional;
        }
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            if (token.startsWith("--")) {
                if (i + 1 < args.length && args[i + 1] != null && !args[i + 1].startsWith("--")) {
                    i++;
                }
                continue;
            }
            positional.add(token);
        }
        return positional;
    }

    private static String readOption(String[] args, String optionName) {
        if (args == null || optionName == null || optionName.isBlank()) {
            return null;
        }
        for (int i = 1; i < args.length; i++) {
            if (optionName.equalsIgnoreCase(args[i])) {
                if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("命令参数 `" + optionName + "` 缺少取值");
                }
                return args[i + 1];
            }
        }
        return null;
    }

    private static Integer parseIntegerOption(String[] args, String optionName) {
        String value = readOption(args, optionName);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("命令参数 `" + optionName + "` 需要整数，实际收到: " + value);
        }
    }

    private static List<String> parseCsvOption(String[] args, String optionName) {
        String value = readOption(args, optionName);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(",")).stream()
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .toList();
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record ProtocolCliSnapshot(
            Object mcp,
            Object mcpState,
            Object a2a,
            Object a2aState
    ) {
    }

    private record A2aCardSnapshot(
            String agentId,
            Object card
    ) {
    }
}
