package io.github.zzz8688.diagagent.store;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.github.zzz8688.diagagent.agent.runtime.MemoryCompressionEventPublisher;
import io.github.zzz8688.diagagent.service.MemoryCandidateService;
import io.github.zzz8688.diagagent.agent.runtime.TokenBudgetManager;
import io.github.zzz8688.diagagent.service.MemorySummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SummaryChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(SummaryChatMemory.class);

    private final Object id;
    private final ChatMemoryStore chatMemoryStore;
    private final ChatLanguageModel chatLanguageModel;
    private final MemorySummaryService memorySummaryService;
    private final MemoryCandidateService memoryCandidateService;
    private final MemoryCompressionEventPublisher memoryCompressionEventPublisher;
    private final int maxRecentMessages;
    private final int questionsPerSummary;
    private final int maxTokens;
    private String lastCompactionFingerprint = "";

    private static final int MAX_TOOL_OUTPUT_LENGTH = 10000;
    private static final int KEEP_HEAD_LINES = 50;
    private static final int KEEP_TAIL_LINES = 50;
    private static final String SUMMARY_TYPE = "rolling_session_summary";
    private static final int MAX_TOPIC_LENGTH = 80;
    private static final int AUTO_COMPACT_RECENT_MESSAGES = 8;
    private static final int MINIMAL_CONTEXT_RECENT_MESSAGES = 4;
    private static final double MICRO_COMPACT_MULTIPLIER = 0.75d;
    private static final double AUTO_COMPACT_THRESHOLD = 0.87d;
    private static final double MINIMAL_CONTEXT_THRESHOLD = 0.90d;
    private static final int APPROXIMATE_CHARS_PER_TOKEN = 4;
    private static final TokenBudgetManager MEMORY_BUDGET_MANAGER = TokenBudgetManager.fixed(
            AUTO_COMPACT_THRESHOLD,
            MINIMAL_CONTEXT_THRESHOLD,
            APPROXIMATE_CHARS_PER_TOKEN
    );

    public SummaryChatMemory(Object id,
                             ChatMemoryStore chatMemoryStore,
                             ChatLanguageModel chatLanguageModel,
                             MemorySummaryService memorySummaryService,
                             MemoryCandidateService memoryCandidateService,
                             MemoryCompressionEventPublisher memoryCompressionEventPublisher,
                             int maxRecentMessages,
                             int questionsPerSummary,
                             int maxTokens) {
        this.id = id;
        this.chatMemoryStore = chatMemoryStore;
        this.chatLanguageModel = chatLanguageModel;
        this.memorySummaryService = memorySummaryService;
        this.memoryCandidateService = memoryCandidateService;
        this.memoryCompressionEventPublisher = memoryCompressionEventPublisher;
        this.maxRecentMessages = maxRecentMessages;
        this.questionsPerSummary = questionsPerSummary;
        this.maxTokens = maxTokens;
    }

    public static CompressionRulesSnapshot buildRulesSnapshot(boolean summaryEnabled,
                                                              int maxRecentMessages,
                                                              int questionsPerSummary,
                                                              int maxTokens) {
        return new CompressionRulesSnapshot(
                summaryEnabled,
                SUMMARY_TYPE,
                Math.max(1, maxRecentMessages),
                Math.max(1, questionsPerSummary),
                Math.max(1, maxTokens),
                AUTO_COMPACT_RECENT_MESSAGES,
                MINIMAL_CONTEXT_RECENT_MESSAGES,
                AUTO_COMPACT_THRESHOLD * MICRO_COMPACT_MULTIPLIER,
                AUTO_COMPACT_THRESHOLD,
                MINIMAL_CONTEXT_THRESHOLD,
                APPROXIMATE_CHARS_PER_TOKEN
        );
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> allMessages = chatMemoryStore.getMessages(id);
        return buildRuntimeMessages(allMessages);
    }

    private List<ChatMessage> snipToolOutputs(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>(messages.size());
        int snippedCount = 0;

        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage toolMsg) {
                String text = safeGetText(toolMsg);
                if (text != null && text.length() > MAX_TOOL_OUTPUT_LENGTH) {
                    String snipped = snipText(text);
                    result.add(new ToolExecutionResultMessage(
                            toolMsg.id(),
                            toolMsg.toolName(),
                            snipped
                    ));
                    snippedCount++;
                    continue;
                }
            }
            result.add(message);
        }

        if (snippedCount > 0) {
            log.debug("裁剪了 {} 条工具输出", snippedCount);
        }

        return result;
    }

    private String safeGetText(ChatMessage message) {
        try {
            if (message instanceof UserMessage um) {
                return um.singleText();
            } else if (message instanceof AiMessage am) {
                return am.text();
            } else if (message instanceof ToolExecutionResultMessage tem) {
                return tem.text();
            } else if (message instanceof SystemMessage sm) {
                return sm.text();
            }
        } catch (Exception e) {
            log.warn("获取消息文本失败：{}", e.getMessage());
        }
        return message.toString();
    }

    private String snipText(String text) {
        String[] lines = text.split("\n");
        if (lines.length <= KEEP_HEAD_LINES + KEEP_TAIL_LINES + 2) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < KEEP_HEAD_LINES; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append(String.format("... (共 %d 行，已裁剪以节省上下文) ...\n", lines.length));
        for (int i = lines.length - KEEP_TAIL_LINES; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString().trim();
    }

    private List<ChatMessage> buildRuntimeMessages(List<ChatMessage> allMessages) {
        List<ChatMessage> snippedMessages = snipToolOutputs(allMessages);
        Optional<MemorySummaryDoc> summaryDocOptional = memorySummaryService.getSummary(id.toString());
        if (summaryDocOptional.isEmpty()) {
            return applyBudgetPolicy(snippedMessages);
        }

        MemorySummaryDoc summaryDoc = summaryDocOptional.get();
        int startIndex = Math.min(summaryDoc.getLastSummarizedMessageCount() == null ? 0 : summaryDoc.getLastSummarizedMessageCount(),
                snippedMessages.size());
        List<ChatMessage> recentMessages = new ArrayList<>(snippedMessages.subList(startIndex, snippedMessages.size()));

        if (recentMessages.size() > maxRecentMessages) {
            recentMessages = new ArrayList<>(recentMessages.subList(recentMessages.size() - maxRecentMessages, recentMessages.size()));
        }

        return applyBudgetPolicy(buildCompressedMessages(summaryDoc.getTopic(), summaryDoc.getContent(), recentMessages));
    }

    private List<ChatMessage> buildCompressedMessages(String topic, String summary, List<ChatMessage> recentMessages) {
        List<ChatMessage> compressedMessages = new ArrayList<>();
        compressedMessages.add(new SystemMessage("历史对话摘要（topic: " + topicLabel(topic, summary, id.toString()) + "）：\n" + summary));
        compressedMessages.addAll(recentMessages);
        return compressedMessages;
    }

    private List<ChatMessage> applyBudgetPolicy(List<ChatMessage> runtimeMessages) {
        TokenBudgetManager.BudgetSnapshot snapshot = MEMORY_BUDGET_MANAGER.evaluateMessages(
                runtimeMessages,
                this::safeGetText,
                maxTokens
        );
        if (!snapshot.requiresAutoCompact() || runtimeMessages.size() <= 2) {
            lastCompactionFingerprint = "";
            return runtimeMessages;
        }
        if (snapshot.requiresMinimalContextMode()) {
            log.info("memory 上下文进入最小模式: memoryId={}, usedTokens={}, maxTokens={}",
                    id, snapshot.usedTokens(), snapshot.maxBudgetTokens());
            List<ChatMessage> minimal = buildMinimalContext(runtimeMessages);
            publishContextCompacted(snapshot, runtimeMessages, minimal);
            return minimal;
        }
        log.info("memory 上下文触发自动压缩: memoryId={}, usedTokens={}, maxTokens={}, level={}",
                id, snapshot.usedTokens(), snapshot.maxBudgetTokens(), snapshot.compactionLevel());
        List<ChatMessage> compacted = buildAutoCompactedContext(runtimeMessages);
        publishContextCompacted(snapshot, runtimeMessages, compacted);
        return compacted;
    }

    private List<ChatMessage> buildAutoCompactedContext(List<ChatMessage> runtimeMessages) {
        List<ChatMessage> compacted = new ArrayList<>();
        ChatMessage firstMessage = runtimeMessages.get(0);
        if (firstMessage instanceof SystemMessage) {
            compacted.add(firstMessage);
        }
        int startIndex = Math.max(compacted.size(), runtimeMessages.size() - AUTO_COMPACT_RECENT_MESSAGES);
        compacted.addAll(runtimeMessages.subList(startIndex, runtimeMessages.size()));
        return compacted;
    }

    private List<ChatMessage> buildMinimalContext(List<ChatMessage> runtimeMessages) {
        List<ChatMessage> minimal = new ArrayList<>();
        ChatMessage firstMessage = runtimeMessages.get(0);
        if (firstMessage instanceof SystemMessage systemMessage) {
            minimal.add(systemMessage);
        }
        minimal.add(new SystemMessage("上下文预算已接近上限，当前只保留最小工作集：最近问题、关键观察与摘要。"));
        int startIndex = Math.max(minimal.size() == 2 ? 1 : 0, runtimeMessages.size() - MINIMAL_CONTEXT_RECENT_MESSAGES);
        minimal.addAll(runtimeMessages.subList(startIndex, runtimeMessages.size()));
        return minimal;
    }

    private String generateRollingSummary(String previousSummary, List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return previousSummary == null ? "" : previousSummary;
        }

        StringBuilder conversationText = new StringBuilder();
        for (ChatMessage message : messages) {
            String text = safeGetText(message);
            if (text != null) {
                if (message instanceof UserMessage) {
                    conversationText.append("用户：").append(text).append("\n");
                } else if (message instanceof AiMessage) {
                    conversationText.append("助手：").append(text).append("\n");
                } else if (message instanceof ToolExecutionResultMessage tem) {
                    String shortText = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                    conversationText.append("[工具] ").append(tem.toolName()).append(": ").append(shortText).append("\n");
                }
            }
        }

        String promptText = "你需要维护一份长期记忆摘要。请基于旧摘要和新增对话，输出新的完整摘要。\n" +
                "【必须保留】\n" +
                "1. 当前故障诊断的关键结论和证据\n" +
                "2. 已确认和待确认事项\n" +
                "3. 重要日志、链路、指标、知识库结论\n" +
                "4. 后续回答必须继承的上下文约束\n" +
                "【必须删除】\n" +
                "- 冗长工具原文\n" +
                "- 重复分析\n" +
                "- 过时中间推测\n" +
                "\n【旧摘要】\n" +
                (previousSummary == null || previousSummary.isBlank() ? "（无）" : previousSummary) +
                "\n\n【新增对话】\n" +
                conversationText;

        try {
            List<ChatMessage> chatMessages = List.of(
                    new SystemMessage("你是一个长期记忆压缩器，请输出可持续滚动更新的摘要。摘要必须简洁、稳定、可复用。"),
                    new UserMessage(promptText)
            );
            ChatResponse response = chatLanguageModel.chat(chatMessages);
            return safeGetText(response.aiMessage());
        } catch (Exception e) {
            log.error("生成对话摘要失败", e);
            return "（摘要生成失败）";
        }
    }

    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> messages = new ArrayList<>(chatMemoryStore.getMessages(id));
        messages.add(message);
        chatMemoryStore.updateMessages(id, messages);
        maybeRollupLongTermMemory(messages, message);
        log.debug("添加消息到对话记忆，当前总数：{}", messages.size());
    }

    private void maybeRollupLongTermMemory(List<ChatMessage> messages, ChatMessage latestMessage) {
        if (!(latestMessage instanceof AiMessage)) {
            return;
        }

        String memoryId = id.toString();
        MemorySummaryDoc existing = memorySummaryService.getSummary(memoryId).orElse(null);
        int summarizedUserQuestions = existing != null && existing.getSummarizedUserQuestionCount() != null
                ? existing.getSummarizedUserQuestionCount() : 0;
        int totalUserQuestions = countUserMessages(messages);
        int unsummarizedQuestions = totalUserQuestions - summarizedUserQuestions;

        if (unsummarizedQuestions < questionsPerSummary) {
            return;
        }

        int lastSummarizedMessageCount = existing != null && existing.getLastSummarizedMessageCount() != null
                ? existing.getLastSummarizedMessageCount() : 0;
        List<ChatMessage> deltaMessages = messages.subList(Math.min(lastSummarizedMessageCount, messages.size()), messages.size());
        String previousSummary = existing != null ? existing.getContent() : "";
        String newSummary = generateRollingSummary(previousSummary, deltaMessages);
        int baseVersion = existing != null && existing.getVersion() != null ? existing.getVersion() : 0;
        int nextVersion = baseVersion + 1;
        String topic = buildTopic(deltaMessages, newSummary, memoryId);
        MemorySummaryDoc.SourceMessageRange sourceMessageRange = new MemorySummaryDoc.SourceMessageRange(
                lastSummarizedMessageCount,
                messages.size() - 1,
                summarizedUserQuestions,
                totalUserQuestions
        );
        Map<String, Object> metadata = buildMetadata(deltaMessages, unsummarizedQuestions, totalUserQuestions, nextVersion);

        MemorySummaryDoc summaryDoc = memorySummaryService.saveRollingSummary(
                memoryId,
                topic,
                SUMMARY_TYPE,
                newSummary,
                baseVersion,
                nextVersion,
                sourceMessageRange,
                metadata,
                messages.size(),
                totalUserQuestions
        );
        memoryCandidateService.createOrUpdateFromSummary(summaryDoc);
        memoryCompressionEventPublisher.publishSummaryRefreshed(
                memoryId,
                memoryId,
                topic,
                SUMMARY_TYPE,
                baseVersion,
                nextVersion,
                deltaMessages.size(),
                unsummarizedQuestions,
                totalUserQuestions,
                messages.size()
        );
    }

    private void publishContextCompacted(TokenBudgetManager.BudgetSnapshot snapshot,
                                         List<ChatMessage> originalMessages,
                                         List<ChatMessage> compactedMessages) {
        if (snapshot == null || originalMessages == null || compactedMessages == null) {
            return;
        }
        String fingerprint = snapshot.compactionLevel()
                + "|" + snapshot.usedTokens()
                + "|" + originalMessages.size()
                + "|" + compactedMessages.size()
                + "|" + snapshot.requiresMinimalContextMode();
        if (fingerprint.equals(lastCompactionFingerprint)) {
            return;
        }
        lastCompactionFingerprint = fingerprint;
        memoryCompressionEventPublisher.publishContextCompacted(
                id.toString(),
                id.toString(),
                snapshot.compactionLevel(),
                snapshot.requiresMinimalContextMode(),
                originalMessages.size(),
                compactedMessages.size(),
                snapshot.usedTokens(),
                snapshot.maxBudgetTokens(),
                !originalMessages.isEmpty() && originalMessages.get(0) instanceof SystemMessage
        );
    }

    private int countUserMessages(List<ChatMessage> messages) {
        int count = 0;
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void clear() {
        chatMemoryStore.deleteMessages(id);
        memorySummaryService.deleteByMemoryId(id.toString());
        log.info("清空对话记忆");
    }

    private String buildTopic(List<ChatMessage> deltaMessages, String summary, String memoryId) {
        for (int i = deltaMessages.size() - 1; i >= 0; i--) {
            ChatMessage message = deltaMessages.get(i);
            if (message instanceof UserMessage userMessage) {
                String text = normalizeTopicText(userMessage.singleText());
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return topicLabel(null, summary, memoryId);
    }

    private String topicLabel(String topic, String summary, String memoryId) {
        String normalizedTopic = normalizeTopicText(topic);
        if (!normalizedTopic.isBlank()) {
            return normalizedTopic;
        }
        String normalizedSummary = normalizeTopicText(summary);
        if (!normalizedSummary.isBlank()) {
            return normalizedSummary;
        }
        return "memory:" + memoryId;
    }

    private String normalizeTopicText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() > MAX_TOPIC_LENGTH) {
            return normalized.substring(0, MAX_TOPIC_LENGTH);
        }
        return normalized;
    }

    private Map<String, Object> buildMetadata(List<ChatMessage> deltaMessages,
                                              int unsummarizedQuestions,
                                              int totalUserQuestions,
                                              int version) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("summaryStrategy", "rolling");
        metadata.put("summaryType", SUMMARY_TYPE);
        metadata.put("deltaMessageCount", deltaMessages.size());
        metadata.put("deltaUserQuestionCount", unsummarizedQuestions);
        metadata.put("totalUserQuestionCount", totalUserQuestions);
        metadata.put("maxRecentMessages", maxRecentMessages);
        metadata.put("maxTokens", maxTokens);
        metadata.put("version", version);
        return metadata;
    }

    public record CompressionRulesSnapshot(
            boolean summaryEnabled,
            String summaryType,
            int maxRecentMessages,
            int questionsPerSummary,
            int maxTokens,
            int autoCompactRecentMessages,
            int minimalContextRecentMessages,
            double microCompactThreshold,
            double autoCompactThreshold,
            double minimalContextThreshold,
            int approximateCharsPerToken
    ) {
    }
}
