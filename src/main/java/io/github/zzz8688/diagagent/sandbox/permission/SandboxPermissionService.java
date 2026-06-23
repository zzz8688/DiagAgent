package io.github.zzz8688.diagagent.sandbox.permission;

import io.github.zzz8688.diagagent.config.SessionContext;
import io.github.zzz8688.diagagent.sandbox.SandboxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SandboxPermissionService {

    private final SandboxProperties sandboxProperties;

    public SandboxPermissionContext buildContext(String workingDirectory) {
        List<SandboxPermissionRule> allowRules = new ArrayList<>();
        List<SandboxPermissionRule> denyRules = new ArrayList<>();
        List<SandboxPermissionRule> askRules = new ArrayList<>();
        for (SandboxProperties.RuleProperties ruleProperties : sandboxProperties.getRules()) {
            SandboxPermissionRule rule = new SandboxPermissionRule(
                    ruleProperties.getAction(),
                    defaultText(ruleProperties.getToolSelector(), "*"),
                    defaultText(ruleProperties.getTargetPattern(), "*"),
                    ruleProperties.getSource(),
                    ruleProperties.getReason(),
                    ruleProperties.isReadOnly()
            );
            if (rule.action() == SandboxRuleAction.ALLOW) {
                allowRules.add(rule);
            } else if (rule.action() == SandboxRuleAction.DENY) {
                denyRules.add(rule);
            } else {
                askRules.add(rule);
            }
        }
        return new SandboxPermissionContext(
                sandboxProperties.getMode(),
                SessionContext.getSessionId(),
                null,
                workingDirectory,
                allowRules,
                denyRules,
                askRules,
                false,
                false,
                sandboxProperties.getMode() == SandboxPermissionMode.AUTO
        );
    }

    public SandboxPermissionDecision evaluate(SandboxPermissionRequest request) {
        SandboxPermissionContext context = buildContext(request.workingDirectory());
        SandboxPermissionRule denyRule = findMatchedRule(context.denyRules(), request);
        if (denyRule != null) {
            return new SandboxPermissionDecision(
                    SandboxDecisionType.DENY,
                    defaultText(denyRule.reason(), "Matched deny rule"),
                    formatMatchedRule(denyRule),
                    normalizeInput(request),
                    true
            );
        }

        SandboxPermissionRule allowRule = findMatchedRule(context.allowRules(), request);
        if (allowRule != null) {
            return new SandboxPermissionDecision(
                    SandboxDecisionType.ALLOW,
                    defaultText(allowRule.reason(), "Matched allow rule"),
                    formatMatchedRule(allowRule),
                    normalizeInput(request),
                    true
            );
        }

        SandboxPermissionRule askRule = findMatchedRule(context.askRules(), request);
        if (askRule != null) {
            return new SandboxPermissionDecision(
                    SandboxDecisionType.ASK,
                    defaultText(askRule.reason(), "Matched ask rule"),
                    formatMatchedRule(askRule),
                    normalizeInput(request),
                    true
            );
        }

        if (context.mode() == SandboxPermissionMode.DONT_ASK) {
            return new SandboxPermissionDecision(
                    SandboxDecisionType.DENY,
                    "Sandbox mode DONT_ASK blocks requests without an allow rule.",
                    "mode:DONT_ASK",
                    normalizeInput(request),
                    true
            );
        }

        if (context.mode() == SandboxPermissionMode.PLAN) {
            return new SandboxPermissionDecision(
                    SandboxDecisionType.ASK,
                    "Sandbox mode PLAN requires approval before execution.",
                    "mode:PLAN",
                    normalizeInput(request),
                    true
            );
        }

        if (context.mode() == SandboxPermissionMode.BYPASS_PERMISSIONS) {
            return new SandboxPermissionDecision(
                    SandboxDecisionType.ALLOW,
                    "Sandbox mode BYPASS_PERMISSIONS allows execution.",
                    "mode:BYPASS_PERMISSIONS",
                    normalizeInput(request),
                    false
            );
        }

        return new SandboxPermissionDecision(
                SandboxDecisionType.ALLOW,
                "Sandbox default allow: execution remains constrained by backend profile.",
                "default:backend-constrained",
                normalizeInput(request),
                true
        );
    }

    private SandboxPermissionRule findMatchedRule(List<SandboxPermissionRule> rules, SandboxPermissionRequest request) {
        return rules.stream()
                .filter(rule -> matchesTool(rule.toolSelector(), request.toolName()))
                .filter(rule -> matchesTarget(rule.targetPattern(), request))
                .findFirst()
                .orElse(null);
    }

    private boolean matchesTool(String selector, String toolName) {
        String normalizedSelector = defaultText(selector, "*").trim().toLowerCase(Locale.ROOT);
        String normalizedToolName = defaultText(toolName, "").trim().toLowerCase(Locale.ROOT);
        return normalizedSelector.equals("*") || normalizedSelector.equals(normalizedToolName);
    }

    private boolean matchesTarget(String targetPattern, SandboxPermissionRequest request) {
        String pattern = defaultText(targetPattern, "*").trim();
        if (pattern.isBlank() || pattern.equals("*")) {
            return true;
        }
        String command = defaultText(String.valueOf(request.arguments().getOrDefault("command", "")), "");
        if (pattern.regionMatches(true, 0, "prefix:", 0, 7)) {
            String prefix = pattern.substring(7).trim().toLowerCase(Locale.ROOT);
            return command.toLowerCase(Locale.ROOT).startsWith(prefix);
        }
        if (pattern.regionMatches(true, 0, "domain:", 0, 7)) {
            String domain = pattern.substring(7).trim().toLowerCase(Locale.ROOT);
            return extractHosts(command).stream().anyMatch(host -> host.equals(domain) || host.endsWith("." + domain));
        }
        if (pattern.regionMatches(true, 0, "path:", 0, 5)) {
            String pathPattern = pattern.substring(5).trim();
            if (request.workingDirectory() == null || request.workingDirectory().isBlank()) {
                return false;
            }
            try {
                Path current = Path.of(request.workingDirectory()).toAbsolutePath().normalize();
                Path configured = Path.of(pathPattern).toAbsolutePath().normalize();
                return current.startsWith(configured);
            } catch (Exception ignored) {
                return false;
            }
        }
        String normalizedPattern = pattern.toLowerCase(Locale.ROOT);
        return command.toLowerCase(Locale.ROOT).contains(normalizedPattern)
                || defaultText(request.targetName(), "").toLowerCase(Locale.ROOT).contains(normalizedPattern);
    }

    private List<String> extractHosts(String command) {
        List<String> hosts = new ArrayList<>();
        for (String token : command.split("\\s+")) {
            try {
                URI uri = URI.create(token.trim());
                if (uri.getHost() != null && !uri.getHost().isBlank()) {
                    hosts.add(uri.getHost().toLowerCase(Locale.ROOT));
                }
            } catch (Exception ignored) {
                // ignore non-url tokens
            }
        }
        return hosts;
    }

    private Map<String, Object> normalizeInput(SandboxPermissionRequest request) {
        return Map.of(
                "toolName", defaultText(request.toolName(), ""),
                "targetType", request.targetType() == null ? "" : request.targetType().name(),
                "targetName", defaultText(request.targetName(), ""),
                "workingDirectory", defaultText(request.workingDirectory(), "")
        );
    }

    private String formatMatchedRule(SandboxPermissionRule rule) {
        return rule.action() + ":" + rule.toolSelector() + ":" + rule.targetPattern();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
