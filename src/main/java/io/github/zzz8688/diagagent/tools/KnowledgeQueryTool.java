package io.github.zzz8688.diagagent.tools;

import io.github.zzz8688.diagagent.store.KnowledgeBaseService;
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
public class KnowledgeQueryTool {

    private final KnowledgeBaseService knowledgeBaseService;

    @Tool("搜索与症状相关的操作手册、文档和历史解决方案。")
    public String queryKnowledgeBase(String query) {
        log.info("Agent 正在搜索知识库: {}", query);
        List<EmbeddingMatch<TextSegment>> matches = knowledgeBaseService.search(query, 3);

        if (matches.isEmpty()) {
            return "未找到相关操作手册。";
        }

        return matches.stream()
                .map(match -> {
                    String title = match.embedded().metadata().getString("title");
                    String content = match.embedded().text();
                    Double score = match.score();
                    return String.format("### %s (相关度: %.2f)\n%s", title, score, content);
                })
                .collect(Collectors.joining("\n\n"));
    }

    public String queryKnowledge(String query) {
        return queryKnowledgeBase(query);
    }
}
