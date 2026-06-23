package io.github.zzz8688.diagagent.mcp.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zzz8688.diagagent.DiagAgentApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
public final class DiagAgentMcpStdioBootstrap {

    private DiagAgentMcpStdioBootstrap() {
    }

    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DiagAgentApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            DiagAgentMcpRequestHandler requestHandler = context.getBean(DiagAgentMcpRequestHandler.class);
            runLoop(System.in, System.out, objectMapper, requestHandler);
        }
    }

    static void runLoop(InputStream input,
                        OutputStream output,
                        ObjectMapper objectMapper,
                        DiagAgentMcpRequestHandler requestHandler) throws IOException {
        while (true) {
            McpProtocolDtos.Request request = readMessage(input, objectMapper);
            if (request == null) {
                return;
            }
            DiagAgentMcpRequestHandler.McpHandledResponse response = requestHandler.handle(request);
            if (response.statusCode() != 204 && response.body() != null) {
                writeMessage(output, objectMapper.writeValueAsBytes(response.body()));
            }
            for (McpProtocolDtos.Notification notification : response.notifications()) {
                writeMessage(output, objectMapper.writeValueAsBytes(notification));
            }
        }
    }

    private static McpProtocolDtos.Request readMessage(InputStream input, ObjectMapper objectMapper) throws IOException {
        int contentLength = 0;
        while (true) {
            String line = readAsciiLine(input);
            if (line == null) {
                return null;
            }
            if (line.isEmpty()) {
                break;
            }
            String lower = line.toLowerCase();
            if (lower.startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        if (contentLength <= 0) {
            throw new IOException("MCP 请求缺少有效的 Content-Length");
        }
        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new IOException("MCP 请求体不完整");
        }
        return objectMapper.readValue(body, McpProtocolDtos.Request.class);
    }

    private static void writeMessage(OutputStream output, byte[] body) throws IOException {
        output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next < 0) {
                if (buffer.size() == 0) {
                    return null;
                }
                break;
            }
            if (next == '\n') {
                break;
            }
            if (next != '\r') {
                buffer.write(next);
            }
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }
}
