package io.github.zzz8688.diagagent.app.cli;

import io.github.zzz8688.diagagent.config.A2aProperties;
import io.github.zzz8688.diagagent.config.DingtalkMcpProperties;
import io.github.zzz8688.diagagent.protocol.DefaultProtocolTransportFactory;
import io.github.zzz8688.diagagent.a2a.A2aBootstrapService;
import io.github.zzz8688.diagagent.a2a.A2aClientAdapter;
import io.github.zzz8688.diagagent.a2a.A2aRegistryService;
import io.github.zzz8688.diagagent.mcp.McpBootstrapService;
import io.github.zzz8688.diagagent.mcp.McpRegistryService;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableConfigurationProperties({DingtalkMcpProperties.class, A2aProperties.class})
@Import({
        DefaultProtocolTransportFactory.class,
        McpRegistryService.class,
        McpBootstrapService.class,
        A2aRegistryService.class,
        A2aClientAdapter.class,
        A2aBootstrapService.class
})
public class DiagAgentProtocolCliApplication {
}
