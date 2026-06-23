package io.github.zzz8688.diagagent.tools;

import dev.langchain4j.agent.tool.Tool;
import io.github.zzz8688.diagagent.store.MemoryFileSearchResult;
import io.github.zzz8688.diagagent.store.MemoryFileService;
import io.github.zzz8688.diagagent.tools.registry.PlatformTool;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class MemoryQueryTool extends BaseTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryQueryTool.class);

    private final MemoryFileService memoryFileService;

    @PlatformTool(description = "搜索 durable memory files 中沉淀的长期经验、偏好、流程和历史诊断结论。仅在当前事实证据不足、还未形成结论或需要备选排查方向时使用；返回内容属于历史经验提示，不直接等同于当前问题的事实证据。")
    @Tool("搜索 durable memory files 中沉淀的长期经验、偏好、流程和历史诊断结论。仅在当前事实证据不足、还未形成结论或需要备选排查方向时使用；返回内容属于历史经验提示，不直接等同于当前问题的事实证据。")
    public String queryMemoryFiles(String query) {
        log.info("Agent 正在搜索 memory files: {}", query);
        beforeToolCall("queryMemoryFiles", query);
        try {
            checkCancelled();
            List<MemoryFileSearchResult> matches = memoryFileService.search(query, 8);
            checkCancelled();

            if (matches.isEmpty()) {
                return "未找到相关长期经验。";
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
                        if (title == null || title.isBlank()) {
                            title = match.segment().metadata().getString("source");
                        }
                        return String.format(
                                "### %s [%s] (最终: %.2f, 精排: %.2f, 融合: %.2f, 向量: %.2f, BM25: %.2f)\n%s",
                                title,
                                match.retrievalMode(),
                                match.finalScore(),
                                match.rerankScore(),
                                match.fusionScore(),
                                match.vectorScore(),
                                match.keywordScore(),
                                match.segment().text()
                        );
                    })
                    .collect(Collectors.joining("\n\n"));
        } catch (BaseTool.ToolExecutionCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("查询 memory files 失败", e);
            return "查询 memory files 失败: " + e.getMessage();
        }
    }
}
