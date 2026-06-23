package io.github.zzz8688.diagagent.sandbox;

import io.github.zzz8688.diagagent.sandbox.approval.SandboxApprovalBackend;
import io.github.zzz8688.diagagent.sandbox.isolation.SandboxProfile;
import io.github.zzz8688.diagagent.sandbox.permission.SandboxPermissionMode;
import io.github.zzz8688.diagagent.sandbox.permission.SandboxRuleAction;
import io.github.zzz8688.diagagent.sandbox.permission.SandboxRuleSource;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "sandbox")
public class SandboxProperties {

    private boolean enabled = true;
    private SandboxPermissionMode mode = SandboxPermissionMode.DEFAULT;
    private SandboxApprovalBackend approvalBackend = SandboxApprovalBackend.HEADLESS_CALLBACK;
    private boolean autoApproveAskRequests = false;
    private int maxAuditRecords = 200;
    private ProfileProperties defaultProfile = new ProfileProperties();
    private List<RuleProperties> rules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SandboxPermissionMode getMode() {
        return mode;
    }

    public void setMode(SandboxPermissionMode mode) {
        this.mode = mode;
    }

    public SandboxApprovalBackend getApprovalBackend() {
        return approvalBackend;
    }

    public void setApprovalBackend(SandboxApprovalBackend approvalBackend) {
        this.approvalBackend = approvalBackend;
    }

    public boolean isAutoApproveAskRequests() {
        return autoApproveAskRequests;
    }

    public void setAutoApproveAskRequests(boolean autoApproveAskRequests) {
        this.autoApproveAskRequests = autoApproveAskRequests;
    }

    public int getMaxAuditRecords() {
        return maxAuditRecords;
    }

    public void setMaxAuditRecords(int maxAuditRecords) {
        this.maxAuditRecords = maxAuditRecords;
    }

    public ProfileProperties getDefaultProfile() {
        return defaultProfile;
    }

    public void setDefaultProfile(ProfileProperties defaultProfile) {
        this.defaultProfile = defaultProfile;
    }

    public List<RuleProperties> getRules() {
        return rules;
    }

    public void setRules(List<RuleProperties> rules) {
        this.rules = rules;
    }

    public SandboxProfile toSandboxProfile() {
        return new SandboxProfile(
                defaultProfile.profileName,
                defaultProfile.readOnlyFilesystem,
                defaultProfile.allowReadPaths,
                defaultProfile.allowWritePaths,
                defaultProfile.denyReadPaths,
                defaultProfile.denyWritePaths,
                defaultProfile.networkEnabled,
                defaultProfile.allowedDomains,
                defaultProfile.deniedDomains,
                defaultProfile.allowUnsandboxedFallback,
                defaultProfile.maxWallClockTime,
                defaultProfile.maxOutputBytes,
                defaultProfile.environmentAllowlist
        );
    }

    @Data
    public static class ProfileProperties {
        private String profileName = "default";
        private boolean readOnlyFilesystem = true;
        private List<String> allowReadPaths = new ArrayList<>();
        private List<String> allowWritePaths = new ArrayList<>();
        private List<String> denyReadPaths = new ArrayList<>();
        private List<String> denyWritePaths = new ArrayList<>();
        private boolean networkEnabled = false;
        private List<String> allowedDomains = new ArrayList<>();
        private List<String> deniedDomains = new ArrayList<>();
        private boolean allowUnsandboxedFallback = false;
        private Duration maxWallClockTime = Duration.ofSeconds(30);
        private long maxOutputBytes = 30_000;
        private Map<String, String> environmentAllowlist = Map.of();
    }

    @Data
    public static class RuleProperties {
        private SandboxRuleAction action = SandboxRuleAction.ASK;
        private String toolSelector = "*";
        private String targetPattern = "*";
        private SandboxRuleSource source = SandboxRuleSource.LOCAL_SETTINGS;
        private String reason = "";
        private boolean readOnly = false;

        public SandboxRuleAction getAction() {
            return action;
        }

        public void setAction(SandboxRuleAction action) {
            this.action = action;
        }

        public String getToolSelector() {
            return toolSelector;
        }

        public void setToolSelector(String toolSelector) {
            this.toolSelector = toolSelector;
        }

        public String getTargetPattern() {
            return targetPattern;
        }

        public void setTargetPattern(String targetPattern) {
            this.targetPattern = targetPattern;
        }

        public SandboxRuleSource getSource() {
            return source;
        }

        public void setSource(SandboxRuleSource source) {
            this.source = source;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public boolean isReadOnly() {
            return readOnly;
        }

        public void setReadOnly(boolean readOnly) {
            this.readOnly = readOnly;
        }
    }
}
