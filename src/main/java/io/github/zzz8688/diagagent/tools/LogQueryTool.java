package io.github.zzz8688.diagagent.tools;

import io.github.zzz8688.diagagent.store.LogVectorStoreService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LogQueryTool {

    private final LogVectorStoreService logVectorStoreService;

    @Tool("使用语义搜索查找与特定错误或关键字相关的日志。用此工具来发现错误模式。")
    public String queryLogs(String query) {
        log.info("Agent 正在搜索日志: {}", query);
        List<EmbeddingMatch<TextSegment>> matches = logVectorStoreService.search(query, 5);

        if (matches.isEmpty()) {
            return "未找到相关日志。";
        }

        String result = matches.stream()
                .map(match -> {
                    TextSegment segment = match.embedded();
                    String rawLog = segment.metadata().getString("rawLog");
                    Double score = match.score();
                    return String.format("[相关度: %.2f] %s", score, rawLog != null ? rawLog : segment.text());
                })
                .collect(Collectors.joining("\n"));

        log.info("日志搜索结果: {}", result);
        return result;
    }
}
