package io.github.zzz8688.diagagent.store;

import dev.langchain4j.data.segment.TextSegment;

public record KnowledgeSearchResult(
        TextSegment segment,
        double finalScore,
        double fusionScore,
        double rerankScore,
        double vectorScore,
        double keywordScore,
        String retrievalMode
) {
}
