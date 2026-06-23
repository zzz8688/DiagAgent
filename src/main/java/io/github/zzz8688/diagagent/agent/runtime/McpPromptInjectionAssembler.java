package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.mcp.McpPromptRegistration;
import io.github.zzz8688.diagagent.mcp.McpRegistrySnapshot;
import io.github.zzz8688.diagagent.mcp.McpRegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class McpPromptInjectionAssembler {

    private final McpRegistryService mcpRegistryService;
    private final int maxPromptSectionChars;

    public McpPromptInjectionAssembler(McpRegistryService mcpRegistryService,
                                       @Value("${chat.runtime.mcp.max-prompt-section-chars:4000}") int maxPromptSectionChars) {
        this.mcpRegistryService = mcpRegistryService;
        this.maxPromptSectionChars = Math.max(256, maxPromptSectionChars);
    }

    public String capabilityViewVersion() {
        McpRegistrySnapshot snapshot = mcpRegistryService.snapshot();
        return snapshot.capabilitySnapshots().stream()
                .map(capability -> capability.serverId() + ":" + nullSafe(capability.capabilityVersion()))
                .sorted(String::compareToIgnoreCase)
                .reduce((left, right) -> left + "|" + right)
                .orElse("mcp:none");
    }

    public List<PromptInjection> listVisiblePromptInjections() {
        return mcpRegistryService.snapshot().prompts().stream()
                .filter(McpPromptRegistration::userVisible)
                .sorted(Comparator.comparing(McpPromptRegistration::serverId, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(McpPromptRegistration::promptName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toPromptInjection)
                .toList();
    }

    public String renderPromptSection() {
        List<PromptInjection> injections = listVisiblePromptInjections();
        if (injections.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("## MCP Dynamic Instructions\n");
        for (PromptInjection injection : injections) {
            String line = "- [" + injection.serverId() + "/" + injection.promptName() + "] "
                    + injection.instructions();
            if (builder.length() + line.length() + 1 > maxPromptSectionChars) {
                break;
            }
            builder.append(line).append('\n');
        }
        return builder.toString().trim();
    }

    private PromptInjection toPromptInjection(McpPromptRegistration promptRegistration) {
        String instructions = extractInstructions(promptRegistration);
        return new PromptInjection(
                nullSafe(promptRegistration.serverId()),
                nullSafe(promptRegistration.promptName()),
                instructions,
                promptRegistration.metadata() == null ? Map.of() : Map.copyOf(promptRegistration.metadata())
        );
    }

    private String extractInstructions(McpPromptRegistration promptRegistration) {
        List<String> parts = new ArrayList<>();
        if (promptRegistration.description() != null && !promptRegistration.description().isBlank()) {
            parts.add(promptRegistration.description().trim());
        }
        Map<String, Object> metadata = promptRegistration.metadata();
        if (metadata != null) {
            appendMetadataField(parts, metadata, "instructions");
            appendMetadataField(parts, metadata, "promptText");
            appendMetadataField(parts, metadata, "summary");
            appendMetadataField(parts, metadata, "hint");
        }
        String joined = String.join(" | ", parts);
        if (joined.isBlank()) {
            joined = "prompt 已注册，但当前只提供名称和参数描述。";
        }
        return joined.length() > 600 ? joined.substring(0, 600) + "..." : joined;
    }

    private void appendMetadataField(List<String> parts, Map<String, Object> metadata, String fieldName) {
        Object value = metadata.get(fieldName);
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) {
            parts.add(text);
        }
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    public record PromptInjection(
            String serverId,
            String promptName,
            String instructions,
            Map<String, Object> metadata
    ) {
    }
}
