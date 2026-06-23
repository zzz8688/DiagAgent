package io.github.zzz8688.diagagent.mcp.server;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp/server")
@RequiredArgsConstructor
public class DiagAgentMcpServerController {

    private final DiagAgentMcpRequestHandler requestHandler;

    @PostMapping
    public ResponseEntity<?> handle(@RequestBody McpProtocolDtos.Request request) {
        DiagAgentMcpRequestHandler.McpHandledResponse response = requestHandler.handle(request);
        if (response.statusCode() == 204) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(response.statusCode()).body(response.body());
    }
}
