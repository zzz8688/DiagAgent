package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.sandbox.SandboxProperties;
import io.github.zzz8688.diagagent.sandbox.audit.SandboxAuditRecord;
import io.github.zzz8688.diagagent.sandbox.audit.SandboxAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sandbox")
@RequiredArgsConstructor
public class SandboxController {

    private final SandboxProperties sandboxProperties;
    private final SandboxAuditService sandboxAuditService;

    @GetMapping("/config")
    public ResponseEntity<SandboxConfigView> config() {
        SandboxProperties.ProfileProperties profile = sandboxProperties.getDefaultProfile();
        return ResponseEntity.ok(new SandboxConfigView(
                sandboxProperties.isEnabled(),
                enumName(sandboxProperties.getMode()),
                enumName(sandboxProperties.getApprovalBackend()),
                sandboxProperties.isAutoApproveAskRequests(),
                sandboxProperties.getMaxAuditRecords(),
                new SandboxProfileView(
                        safe(profile.getProfileName()),
                        profile.isReadOnlyFilesystem(),
                        safeList(profile.getAllowReadPaths()),
                        safeList(profile.getAllowWritePaths()),
                        safeList(profile.getDenyReadPaths()),
                        safeList(profile.getDenyWritePaths()),
                        profile.isNetworkEnabled(),
                        safeList(profile.getAllowedDomains()),
                        safeList(profile.getDeniedDomains()),
                        profile.isAllowUnsandboxedFallback(),
                        formatDuration(profile.getMaxWallClockTime()),
                        profile.getMaxOutputBytes(),
                        safeStringMap(profile.getEnvironmentAllowlist())
                ),
                safeRules(sandboxProperties.getRules()).stream()
                        .map(rule -> new SandboxRuleView(
                                enumName(rule.getAction()),
                                safe(rule.getToolSelector()),
                                safe(rule.getTargetPattern()),
                                enumName(rule.getSource()),
                                safe(rule.getReason()),
                                rule.isReadOnly()
                        ))
                        .toList()
        ));
    }

    @GetMapping("/audits")
    public ResponseEntity<List<SandboxAuditRecord>> audits(@RequestParam(required = false) String sessionId,
                                                           @RequestParam(required = false) String taskId) {
        String normalizedSessionId = normalize(sessionId);
        String normalizedTaskId = normalize(taskId);
        return ResponseEntity.ok(sandboxAuditService.recentRecords().stream()
                .filter(record -> record != null)
                .filter(record -> matches(record, normalizedSessionId, normalizedTaskId))
                .toList());
    }

    private boolean matches(SandboxAuditRecord record, String sessionId, String taskId) {
        if (record == null) {
            return false;
        }
        if (taskId != null && taskId.equals(record.taskId())) {
            return true;
        }
        if (sessionId != null && sessionId.equals(record.sessionId())) {
            return taskId == null || record.taskId() == null || record.taskId().isBlank();
        }
        return sessionId == null && taskId == null;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String enumName(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String formatDuration(Duration value) {
        return value == null ? "" : value.toString();
    }

    private List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.trim());
        }
        return List.copyOf(normalized);
    }

    private Map<String, String> safeStringMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            normalized.put(entry.getKey().trim(), entry.getValue() == null ? "" : entry.getValue().trim());
        }
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private List<SandboxProperties.RuleProperties> safeRules(List<SandboxProperties.RuleProperties> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        return rules.stream()
                .filter(rule -> rule != null)
                .toList();
    }

    public record SandboxConfigView(
            boolean enabled,
            String mode,
            String approvalBackend,
            boolean autoApproveAskRequests,
            int maxAuditRecords,
            SandboxProfileView defaultProfile,
            List<SandboxRuleView> rules
    ) {
    }

    public record SandboxProfileView(
            String profileName,
            boolean readOnlyFilesystem,
            List<String> allowReadPaths,
            List<String> allowWritePaths,
            List<String> denyReadPaths,
            List<String> denyWritePaths,
            boolean networkEnabled,
            List<String> allowedDomains,
            List<String> deniedDomains,
            boolean allowUnsandboxedFallback,
            String maxWallClockTime,
            long maxOutputBytes,
            Map<String, String> environmentAllowlist
    ) {
    }

    public record SandboxRuleView(
            String action,
            String toolSelector,
            String targetPattern,
            String source,
            String reason,
            boolean readOnly
    ) {
    }
}
