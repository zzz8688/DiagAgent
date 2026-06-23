package io.github.zzz8688.diagagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "a2a")
public class A2aProperties {

    private boolean enabled = true;

    private ClientProperties client = new ClientProperties();

    private SelfProperties self = new SelfProperties();

    private List<RemoteAgentProperties> remotes = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ClientProperties getClient() {
        return client;
    }

    public void setClient(ClientProperties client) {
        this.client = client;
    }

    public SelfProperties getSelf() {
        return self;
    }

    public void setSelf(SelfProperties self) {
        this.self = self;
    }

    public List<RemoteAgentProperties> getRemotes() {
        return remotes;
    }

    public void setRemotes(List<RemoteAgentProperties> remotes) {
        this.remotes = remotes;
    }

    @Data
    public static class SelfProperties {

        private boolean enabled = true;

        private String agentId = "diag-agent-a2a";

        private String name = "DiagAgent";

        private String description = "统一诊断代理平台，负责故障分析、技能编排、MCP 工具治理与人工介入升级。";

        private String endpoint = "http://localhost:8081/a2a";

        private String preferredTransport = "jsonrpc";

        private String protocolVersion = "1.0";

        private String version = "1.0.0";

        private boolean streaming = true;

        private boolean pushNotifications = false;

        private List<String> defaultInputModes = List.of("text");

        private List<String> defaultOutputModes = List.of("text");

        private String documentationUrl = "";

        private List<SkillProperties> skills = defaultSkills();

        private List<SkillProperties> defaultSkills() {
            return List.of(
                    new SkillProperties("incident-diagnosis", "Incident Diagnosis",
                            "接收故障描述并编排指标、日志、知识和工具完成诊断。",
                            List.of("diagnosis", "incident"), List.of("诊断最近 30 分钟的接口错误率飙升")),
                    new SkillProperties("metric-analysis", "Metric Analysis",
                            "负责 Prometheus 指标分析、异常定位和趋势归因。",
                            List.of("metrics", "prometheus"), List.of("分析 CPU 与延迟异常")),
                    new SkillProperties("tool-governance", "Tool Governance",
                            "通过统一 sandbox、approval、audit 保护本地工具和 MCP tools/call。",
                            List.of("sandbox", "security"), List.of("执行只读 shell 排查命令")),
                    new SkillProperties("skill-orchestration", "Skill Orchestration",
                            "根据技能库与上下文生成可执行 skill plan，并分发给主模型或子代理。",
                            List.of("skills", "orchestration"), List.of("运行一个诊断 handoff skill"))
            );
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getPreferredTransport() {
            return preferredTransport;
        }

        public void setPreferredTransport(String preferredTransport) {
            this.preferredTransport = preferredTransport;
        }

        public String getProtocolVersion() {
            return protocolVersion;
        }

        public void setProtocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public boolean isStreaming() {
            return streaming;
        }

        public void setStreaming(boolean streaming) {
            this.streaming = streaming;
        }

        public boolean isPushNotifications() {
            return pushNotifications;
        }

        public void setPushNotifications(boolean pushNotifications) {
            this.pushNotifications = pushNotifications;
        }

        public List<String> getDefaultInputModes() {
            return defaultInputModes;
        }

        public void setDefaultInputModes(List<String> defaultInputModes) {
            this.defaultInputModes = defaultInputModes;
        }

        public List<String> getDefaultOutputModes() {
            return defaultOutputModes;
        }

        public void setDefaultOutputModes(List<String> defaultOutputModes) {
            this.defaultOutputModes = defaultOutputModes;
        }

        public String getDocumentationUrl() {
            return documentationUrl;
        }

        public void setDocumentationUrl(String documentationUrl) {
            this.documentationUrl = documentationUrl;
        }

        public List<SkillProperties> getSkills() {
            return skills;
        }

        public void setSkills(List<SkillProperties> skills) {
            this.skills = skills;
        }
    }

    @Data
    public static class ClientProperties {

        private long connectTimeoutMs = 5000L;

        private long requestTimeoutMs = 30000L;

        private long streamingRequestTimeoutMs = 60000L;

        private long agentCardRefreshIntervalMs = 60000L;

        private String userAgent = "DiagAgent-A2A-Client/1.0";

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getRequestTimeoutMs() {
            return requestTimeoutMs;
        }

        public void setRequestTimeoutMs(long requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
        }

        public long getStreamingRequestTimeoutMs() {
            return streamingRequestTimeoutMs;
        }

        public void setStreamingRequestTimeoutMs(long streamingRequestTimeoutMs) {
            this.streamingRequestTimeoutMs = streamingRequestTimeoutMs;
        }

        public long getAgentCardRefreshIntervalMs() {
            return agentCardRefreshIntervalMs;
        }

        public void setAgentCardRefreshIntervalMs(long agentCardRefreshIntervalMs) {
            this.agentCardRefreshIntervalMs = agentCardRefreshIntervalMs;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
    }

    @Data
    public static class RemoteAgentProperties {

        private boolean enabled = false;

        private String agentId;

        private String name;

        private String description = "";

        private String endpoint;

        private String preferredTransport = "jsonrpc";

        private String protocolVersion = "1.0";

        private String version = "1.0.0";

        private boolean streaming = true;

        private boolean pushNotifications = false;

        private List<String> defaultInputModes = List.of("text");

        private List<String> defaultOutputModes = List.of("text");

        private String documentationUrl = "";

        private List<SkillProperties> skills = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getPreferredTransport() {
            return preferredTransport;
        }

        public void setPreferredTransport(String preferredTransport) {
            this.preferredTransport = preferredTransport;
        }

        public String getProtocolVersion() {
            return protocolVersion;
        }

        public void setProtocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public boolean isStreaming() {
            return streaming;
        }

        public void setStreaming(boolean streaming) {
            this.streaming = streaming;
        }

        public boolean isPushNotifications() {
            return pushNotifications;
        }

        public void setPushNotifications(boolean pushNotifications) {
            this.pushNotifications = pushNotifications;
        }

        public List<String> getDefaultInputModes() {
            return defaultInputModes;
        }

        public void setDefaultInputModes(List<String> defaultInputModes) {
            this.defaultInputModes = defaultInputModes;
        }

        public List<String> getDefaultOutputModes() {
            return defaultOutputModes;
        }

        public void setDefaultOutputModes(List<String> defaultOutputModes) {
            this.defaultOutputModes = defaultOutputModes;
        }

        public String getDocumentationUrl() {
            return documentationUrl;
        }

        public void setDocumentationUrl(String documentationUrl) {
            this.documentationUrl = documentationUrl;
        }

        public List<SkillProperties> getSkills() {
            return skills;
        }

        public void setSkills(List<SkillProperties> skills) {
            this.skills = skills;
        }
    }

    @Data
    public static class SkillProperties {

        private String id;

        private String name;

        private String description = "";

        private List<String> tags = List.of();

        private List<String> examples = List.of();

        public SkillProperties() {
        }

        public SkillProperties(String id, String name, String description, List<String> tags, List<String> examples) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.tags = tags == null ? List.of() : List.copyOf(tags);
            this.examples = examples == null ? List.of() : List.copyOf(examples);
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public List<String> getExamples() {
            return examples;
        }

        public void setExamples(List<String> examples) {
            this.examples = examples;
        }
    }
}
