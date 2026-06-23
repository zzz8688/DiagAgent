package io.github.zzz8688.diagagent.agent.runtime;

import dev.langchain4j.data.message.ChatMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@Component
public class TokenBudgetManager {

    private final double autoCompactThreshold;
    private final double hardStopThreshold;
    private final int approximateCharsPerToken;

    @Autowired
    public TokenBudgetManager(
            @Value("${chat.runtime.token-budget.auto-compact-threshold:0.87}") double autoCompactThreshold,
            @Value("${chat.runtime.token-budget.hard-stop-threshold:0.90}") double hardStopThreshold,
            @Value("${chat.runtime.token-budget.approximate-chars-per-token:4}") int approximateCharsPerToken
    ) {
        this(autoCompactThreshold, hardStopThreshold, approximateCharsPerToken, true);
    }

    private TokenBudgetManager(double autoCompactThreshold, double hardStopThreshold, int approximateCharsPerToken,
                               boolean normalizeValues) {
        this.autoCompactThreshold = normalizeThreshold(autoCompactThreshold, 0.87d);
        this.hardStopThreshold = normalizeThreshold(hardStopThreshold, 0.90d);
        this.approximateCharsPerToken = Math.max(1, approximateCharsPerToken);
    }

    public static TokenBudgetManager fixed(double autoCompactThreshold, double hardStopThreshold,
                                           int approximateCharsPerToken) {
        return new TokenBudgetManager(autoCompactThreshold, hardStopThreshold, approximateCharsPerToken, true);
    }

    public BudgetSnapshot evaluateTexts(List<String> textSegments, int maxBudgetTokens) {
        int safeMaxBudget = Math.max(1, maxBudgetTokens);
        int usedTokens = estimateTokens(textSegments);
        double usageRatio = safeMaxBudget == 0 ? 0d : (double) usedTokens / safeMaxBudget;
        BudgetStatus status = resolveStatus(usageRatio);
        return new BudgetSnapshot(
                usedTokens,
                safeMaxBudget,
                usageRatio,
                status,
                status.compactionLevel(),
                status.requiresMinimalContextMode()
        );
    }

    public <T> BudgetSnapshot evaluate(List<T> items, Function<T, String> textExtractor, int maxBudgetTokens) {
        Objects.requireNonNull(textExtractor, "textExtractor");
        List<String> segments = new ArrayList<>();
        if (items != null) {
            for (T item : items) {
                String text = textExtractor.apply(item);
                if (text != null && !text.isBlank()) {
                    segments.add(text);
                }
            }
        }
        return evaluateTexts(segments, maxBudgetTokens);
    }

    public BudgetSnapshot evaluateMessages(List<? extends ChatMessage> messages,
                                           Function<ChatMessage, String> textExtractor,
                                           int maxBudgetTokens) {
        Objects.requireNonNull(textExtractor, "textExtractor");
        List<String> segments = new ArrayList<>();
        if (messages != null) {
            for (ChatMessage message : messages) {
                String text = textExtractor.apply(message);
                if (text != null && !text.isBlank()) {
                    segments.add(text);
                }
            }
        }
        return evaluateTexts(segments, maxBudgetTokens);
    }

    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil((double) text.length() / approximateCharsPerToken));
    }

    public int estimateTokens(List<String> textSegments) {
        int total = 0;
        if (textSegments == null) {
            return total;
        }
        for (String textSegment : textSegments) {
            total += estimateTokens(textSegment);
        }
        return total;
    }

    public boolean requiresAutoCompact(BudgetSnapshot snapshot) {
        return snapshot != null && snapshot.status().requiresAutoCompact();
    }

    public boolean requiresHardStop(BudgetSnapshot snapshot) {
        return snapshot != null && snapshot.status().requiresHardStop();
    }

    private BudgetStatus resolveStatus(double usageRatio) {
        if (usageRatio >= hardStopThreshold) {
            return BudgetStatus.HARD_STOP;
        }
        if (usageRatio >= autoCompactThreshold) {
            return BudgetStatus.AUTO_COMPACT;
        }
        if (usageRatio >= autoCompactThreshold * 0.75d) {
            return BudgetStatus.MICRO_COMPACT;
        }
        return BudgetStatus.NORMAL;
    }

    private double normalizeThreshold(double threshold, double fallback) {
        if (threshold <= 0d || threshold >= 1d) {
            return fallback;
        }
        return threshold;
    }

    public enum BudgetStatus {
        NORMAL("none", false, false, false),
        MICRO_COMPACT("micro", true, false, false),
        AUTO_COMPACT("auto", true, false, false),
        HARD_STOP("minimal", true, true, true);

        private final String compactionLevel;
        private final boolean requiresCompaction;
        private final boolean requiresHardStop;
        private final boolean requiresMinimalContextMode;

        BudgetStatus(String compactionLevel,
                     boolean requiresCompaction,
                     boolean requiresHardStop,
                     boolean requiresMinimalContextMode) {
            this.compactionLevel = compactionLevel;
            this.requiresCompaction = requiresCompaction;
            this.requiresHardStop = requiresHardStop;
            this.requiresMinimalContextMode = requiresMinimalContextMode;
        }

        public String compactionLevel() {
            return compactionLevel;
        }

        public boolean requiresCompaction() {
            return requiresCompaction;
        }

        public boolean requiresAutoCompact() {
            return this == MICRO_COMPACT || this == AUTO_COMPACT || this == HARD_STOP;
        }

        public boolean requiresHardStop() {
            return requiresHardStop;
        }

        public boolean requiresMinimalContextMode() {
            return requiresMinimalContextMode;
        }
    }

    public record BudgetSnapshot(
            int usedTokens,
            int maxBudgetTokens,
            double usageRatio,
            BudgetStatus status,
            String compactionLevel,
            boolean minimalContextMode
    ) {

        public boolean requiresAutoCompact() {
            return status.requiresAutoCompact();
        }

        public boolean requiresHardStop() {
            return status.requiresHardStop();
        }

        public boolean requiresMinimalContextMode() {
            return minimalContextMode || status.requiresMinimalContextMode();
        }
    }
}
