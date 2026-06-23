package io.github.zzz8688.diagagent.tools;

import io.github.zzz8688.diagagent.store.KnowledgeBaseService;
import io.github.zzz8688.diagagent.store.KnowledgeSearchResult;
import dev.langchain4j.agent.tool.Tool;
import io.github.zzz8688.diagagent.tools.registry.PlatformTool;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class KnowledgeQueryTool extends BaseTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQueryTool.class);

    private final KnowledgeBaseService knowledgeBaseService;

    @PlatformTool(description = "搜索与症状相关的操作手册、文档和历史解决方案。优先在已有部分当前事实证据后再使用，用于补充规则、案例和排查思路，不应替代 logs、metrics、trace 等运行时事实。")
    @Tool("搜索与症状相关的操作手册、文档和历史解决方案。优先在已有部分当前事实证据后再使用，用于补充规则、案例和排查思路，不应替代 logs、metrics、trace 等运行时事实。")
    public String queryKnowledgeBase(String query) {
        log.info("Agent 正在搜索知识库: {}", query);
        beforeToolCall("queryKnowledgeBase", query);
        try {
            checkCancelled();
            List<KnowledgeSearchResult> matches = knowledgeBaseService.search(query, 10);
            checkCancelled();

            if (matches.isEmpty()) {
                return "未找到相关操作手册。";
            }

            return matches.stream()
                    .collect(Collectors.toMap(
                            match -> {
                                checkCancelled();
                                String id = match.segment().metadata().getString("id");
                                return id != null ? id : match.segment().metadata().getString("source");
                            },
                            match -> match,
                            (existing, replacement) -> existing.finalScore() >= replacement.finalScore() ? existing : replacement
                    ))
                    .values()
                    .stream()
                    .sorted((a, b) -> Double.compare(b.finalScore(), a.finalScore()))
                    .limit(5)
                    .map(match -> {
                        checkCancelled();
                        String title = match.segment().metadata().getString("title");
                        if (title == null) {
                            title = match.segment().metadata().getString("source");
                        }
                        String content = match.segment().text();
                        return String.format(
                                "### %s [%s] (最终: %.2f, 精排: %.2f, 融合: %.2f, 向量: %.2f, BM25: %.2f)\n%s",
                                title,
                                match.retrievalMode(),
                                match.finalScore(),
                                match.rerankScore(),
                                match.fusionScore(),
                                match.vectorScore(),
                                match.keywordScore(),
                                content
                        );
                    })
                    .collect(Collectors.joining("\n\n"));
        } catch (BaseTool.ToolExecutionCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询知识库失败", e);
            return "查询知识库失败: " + e.getMessage();
        }
    }
}
