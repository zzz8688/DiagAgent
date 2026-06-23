package io.github.zzz8688.diagagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "mcp.dingtalk")
public class DingtalkMcpProperties {

    private boolean enabled = true;

    private String sseEndpoint = "https://dashscope.aliyuncs.com/api/v1/mcps/dingtalk-mcp/sse";

    private String apiKey = "";

    private String authorizationHeader = "Authorization";

    private long requestTimeoutMs = 15000L;

    private String dingClientId = "";

    private String dingClientSecret = "";

    private String openApiBaseUrl = "https://oapi.dingtalk.com";

    private int departmentUserPageSize = 100;

    private Long agentId;

    private boolean toAllUser = true;

    private List<String> userIdList = List.of();

    private List<String> deptIdList = List.of();

    private String messageType = "markdown";

    private String linkMessageUrl = "";

    private String linkPicUrl = "";

    private List<String> preferredToolNames = List.of("sendNotice");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSseEndpoint() {
        return sseEndpoint;
    }

    public void setSseEndpoint(String sseEndpoint) {
        this.sseEndpoint = sseEndpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getAuthorizationHeader() {
        return authorizationHeader;
    }

    public void setAuthorizationHeader(String authorizationHeader) {
        this.authorizationHeader = authorizationHeader;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public String getDingClientId() {
        return dingClientId;
    }

    public void setDingClientId(String dingClientId) {
        this.dingClientId = dingClientId;
    }

    public String getDingClientSecret() {
        return dingClientSecret;
    }

    public void setDingClientSecret(String dingClientSecret) {
        this.dingClientSecret = dingClientSecret;
    }

    public String getOpenApiBaseUrl() {
        return openApiBaseUrl;
    }

    public void setOpenApiBaseUrl(String openApiBaseUrl) {
        this.openApiBaseUrl = openApiBaseUrl;
    }

    public int getDepartmentUserPageSize() {
        return departmentUserPageSize;
    }

    public void setDepartmentUserPageSize(int departmentUserPageSize) {
        this.departmentUserPageSize = departmentUserPageSize;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public boolean isToAllUser() {
        return toAllUser;
    }

    public void setToAllUser(boolean toAllUser) {
        this.toAllUser = toAllUser;
    }

    public List<String> getUserIdList() {
        return userIdList;
    }

    public void setUserIdList(List<String> userIdList) {
        this.userIdList = userIdList;
    }

    public List<String> getDeptIdList() {
        return deptIdList;
    }

    public void setDeptIdList(List<String> deptIdList) {
        this.deptIdList = deptIdList;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getLinkMessageUrl() {
        return linkMessageUrl;
    }

    public void setLinkMessageUrl(String linkMessageUrl) {
        this.linkMessageUrl = linkMessageUrl;
    }

    public String getLinkPicUrl() {
        return linkPicUrl;
    }

    public void setLinkPicUrl(String linkPicUrl) {
        this.linkPicUrl = linkPicUrl;
    }

    public List<String> getPreferredToolNames() {
        return preferredToolNames;
    }

    public void setPreferredToolNames(List<String> preferredToolNames) {
        this.preferredToolNames = preferredToolNames;
    }
}
