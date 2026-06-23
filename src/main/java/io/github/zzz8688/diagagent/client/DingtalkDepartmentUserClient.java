package io.github.zzz8688.diagagent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zzz8688.diagagent.config.DingtalkMcpProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DingtalkDepartmentUserClient {

    private static final Logger log = LoggerFactory.getLogger(DingtalkDepartmentUserClient.class);

    private final DingtalkMcpProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public List<String> resolveUserIds(List<String> deptIdList) {
        List<String> normalizedDeptIds = normalizeDeptIds(deptIdList);
        if (normalizedDeptIds.isEmpty()) {
            return List.of();
        }

        String clientId = defaultText(properties.getDingClientId(), "").trim();
        String clientSecret = defaultText(properties.getDingClientSecret(), "").trim();
        if (clientId.isBlank() || clientSecret.isBlank()) {
            log.warn("未配置 Ding_Client_ID 或 Ding_Client_Secret，跳过部门用户解析");
            return List.of();
        }

        try {
            String accessToken = getAccessToken(clientId, clientSecret);
            Set<String> userIds = new LinkedHashSet<>();
            for (String deptId : normalizedDeptIds) {
                userIds.addAll(listUsersByDept(accessToken, deptId));
            }
            return new ArrayList<>(userIds);
        } catch (Exception e) {
            log.error("根据部门解析钉钉用户失败", e);
            return List.of();
        }
    }

    private String getAccessToken(String clientId, String clientSecret) throws IOException, InterruptedException {
        String baseUrl = defaultText(properties.getOpenApiBaseUrl(), "https://oapi.dingtalk.com").trim();
        String url = baseUrl + "/gettoken?appkey=" + encode(clientId) + "&appsecret=" + encode(clientSecret);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofMillis(Math.max(properties.getRequestTimeoutMs(), 1000L)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = readJson(response.body());
        assertSuccess(root, "获取钉钉 access_token");
        String accessToken = root.path("access_token").asText("");
        if (accessToken.isBlank()) {
            throw new IllegalStateException("钉钉 access_token 为空");
        }
        return accessToken;
    }

    private List<String> listUsersByDept(String accessToken, String deptId) throws IOException, InterruptedException {
        String baseUrl = defaultText(properties.getOpenApiBaseUrl(), "https://oapi.dingtalk.com").trim();
        String url = baseUrl + "/topapi/v2/user/list?access_token=" + encode(accessToken);
        int pageSize = Math.max(1, properties.getDepartmentUserPageSize());
        long cursor = 0L;
        boolean hasMore = true;
        Set<String> userIds = new LinkedHashSet<>();

        while (hasMore) {
            String payload = objectMapper.writeValueAsString(new UserListRequest(Long.parseLong(deptId), cursor, pageSize));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .timeout(Duration.ofMillis(Math.max(properties.getRequestTimeoutMs(), 1000L)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = readJson(response.body());
            assertSuccess(root, "按部门获取钉钉用户列表");

            JsonNode result = root.path("result");
            JsonNode list = result.path("list");
            if (list.isArray()) {
                for (JsonNode item : list) {
                    String userId = item.path("userid").asText("");
                    if (userId.isBlank()) {
                        userId = item.path("useridList").asText("");
                    }
                    if (!userId.isBlank()) {
                        userIds.add(userId.trim());
                    }
                }
            }

            hasMore = result.path("has_more").asBoolean(false);
            if (hasMore) {
                cursor = result.path("next_cursor").asLong(cursor + pageSize);
            }
        }

        return new ArrayList<>(userIds);
    }

    private JsonNode readJson(String body) throws IOException {
        return objectMapper.readTree(body == null ? "{}" : body);
    }

    private void assertSuccess(JsonNode root, String action) {
        int errCode = root.path("errcode").asInt(0);
        if (errCode != 0) {
            String errMsg = root.path("errmsg").asText("unknown error");
            throw new IllegalStateException(action + "失败: errcode=" + errCode + ", errmsg=" + errMsg);
        }
    }

    private List<String> normalizeDeptIds(List<String> deptIdList) {
        if (deptIdList == null) {
            return List.of();
        }
        return deptIdList.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record UserListRequest(Long dept_id, Long cursor, Integer size) {
    }
}
