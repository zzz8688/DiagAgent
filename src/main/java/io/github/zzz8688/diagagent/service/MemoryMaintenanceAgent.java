package io.github.zzz8688.diagagent.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.zzz8688.diagagent.config.SystemPromptConfig;
import io.github.zzz8688.diagagent.config.MemoryFileRagConfig;
import io.github.zzz8688.diagagent.entity.MemoryCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryMaintenanceAgent {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final List<String> FORGET_SIGNALS = List.of("误报", "过期", "已过时", "不再适用", "无需保留", "临时结论");

    private final MemoryCandidateService memoryCandidateService;
    private final MemoryFileRagConfig memoryFileRagConfig;
    private final ManagedMemoryGovernanceService managedMemoryGovernanceService;
    private final SystemPromptConfig systemPromptConfig;

    @Qualifier("chatLanguageModel")
    private final ChatLanguageModel chatLanguageModel;

    @Value("${memory.files.path:./memory}")
    private String memoryFilesPath;

    @Value("${memory.maintenance.enabled:true}")
    private boolean enabled;

    @Value("${memory.maintenance.batch-size:5}")
    private int batchSize;

    @Value("${memory.maintenance.min-summary-length:80}")
    private int minSummaryLength;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile MaintenanceRunSummary lastRunSummary = MaintenanceRunSummary.idle();

    @Scheduled(fixedDelayString = "${memory.maintenance.fixed-delay-ms:60000}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        runNow("scheduled");
    }

    public MaintenanceRunSummary runNow(String trigger) {
        if (!running.compareAndSet(false, true)) {
            return lastRunSummary;
        }
        Instant startedAt = Instant.now();
        int processed = 0;
        int promoted = 0;
        int merged = 0;
        int corrected = 0;
        int forgotten = 0;
        int rejected = 0;
        int failed = 0;
        boolean touchedFiles = false;
        List<String> handledCandidateIds = new ArrayList<>();
        String safeTrigger = trigger == null || trigger.isBlank() ? "manual" : trigger;
        try {
            List<MemoryCandidate> pendingCandidates = memoryCandidateService.listPendingCandidates(batchSize);
            for (MemoryCandidate candidate : pendingCandidates) {
                handledCandidateIds.add(candidate.getId());
                processed++;
                ProcessResult result = processCandidate(candidate);
                touchedFiles = touchedFiles || result.touchedFiles();
                switch (result.status()) {
                    case PROMOTED -> promoted++;
                    case MERGED -> merged++;
                    case CORRECTED -> corrected++;
                    case FORGOTTEN -> forgotten++;
                    case REJECTED -> rejected++;
                    case FAILED -> failed++;
                    default -> {
                    }
                }
            }
            if (touchedFiles) {
                memoryFileRagConfig.loadMemoryFilesIncremental();
            }
            lastRunSummary = new MaintenanceRunSummary(
                    safeTrigger,
                    startedAt.toString(),
                    Instant.now().toString(),
                    processed,
                    promoted,
                    merged,
                    corrected,
                    forgotten,
                    rejected,
                    failed,
                    handledCandidateIds,
                    touchedFiles,
                    processed == 0 ? "当前没有待处理 memory candidates" : "memory maintenance run completed"
            );
            return lastRunSummary;
        } catch (Exception e) {
            log.error("MemoryMaintenanceAgent 执行失败: trigger={}", safeTrigger, e);
            lastRunSummary = new MaintenanceRunSummary(
                    safeTrigger,
                    startedAt.toString(),
                    Instant.now().toString(),
                    processed,
                    promoted,
                    merged,
                    corrected,
                    forgotten,
                    rejected,
                    failed + 1,
                    handledCandidateIds,
                    touchedFiles,
                    "memory maintenance run failed: " + e.getMessage()
            );
            return lastRunSummary;
        } finally {
            running.set(false);
        }
    }

    public MaintenanceRunSummary getLastRunSummary() {
        return lastRunSummary;
    }

    public boolean isRunning() {
        return running.get();
    }

    private ProcessResult processCandidate(MemoryCandidate candidate) {
        if (candidate == null) {
            return new ProcessResult(MemoryCandidate.CandidateStatus.REJECTED, false);
        }
        candidate.setStatus(MemoryCandidate.CandidateStatus.PROCESSING);
        candidate.setUpdatedAt(new Date());
        memoryCandidateService.save(candidate);
        try {
            String summaryContent = normalize(candidate.getSummaryContent());
            if (summaryContent == null || summaryContent.length() < minSummaryLength) {
                return finalizeCandidate(candidate, MemoryCandidate.CandidateStatus.REJECTED,
                        MemoryCandidate.CandidateOperation.SKIP, null,
                        "summary content too short for durable promotion");
            }

            Path targetPath = resolveTargetPath(candidate);
            String relativePath = normalizeMemoryPath(targetPath);
            candidate.setTargetFilePath(relativePath);
            Map<String, String> frontMatter = Files.exists(targetPath) ? readFrontMatter(targetPath) : Map.of();
            String existingMemoryId = frontMatter.getOrDefault("memory_id", "");
            String existingContentHash = frontMatter.getOrDefault("content_hash", "");

            if (containsForgetSignal(summaryContent)) {
                writeManagedMemoryFile(targetPath, candidate, MemoryCandidate.CandidateOperation.FORGET,
                        "forgotten", "根据遗忘规则将该 durable memory 标记为失效。", summaryContent);
                return finalizeCandidate(candidate, MemoryCandidate.CandidateStatus.FORGOTTEN,
                        MemoryCandidate.CandidateOperation.FORGET, relativePath,
                        "forget rule matched");
            }

            if (!existingContentHash.isBlank() && existingContentHash.equals(candidate.getContentHash())) {
                return finalizeCandidate(candidate, MemoryCandidate.CandidateStatus.MERGED,
                        MemoryCandidate.CandidateOperation.MERGE, relativePath,
                        "duplicate candidate merged by content hash");
            }

            MemoryCandidate.CandidateOperation operation = Files.exists(targetPath)
                    ? resolveUpdateOperation(existingMemoryId, candidate.getMemoryId())
                    : MemoryCandidate.CandidateOperation.CREATE;
            String durableContent = buildDurableMemoryContent(candidate, operation);
            writeManagedMemoryFile(targetPath, candidate, operation, "active", durableContent, summaryContent);
            MemoryCandidate.CandidateStatus finalStatus = switch (operation) {
                case CREATE -> MemoryCandidate.CandidateStatus.PROMOTED;
                case MERGE -> MemoryCandidate.CandidateStatus.MERGED;
                case CORRECT -> MemoryCandidate.CandidateStatus.CORRECTED;
                case FORGET -> MemoryCandidate.CandidateStatus.FORGOTTEN;
                case SKIP -> MemoryCandidate.CandidateStatus.REJECTED;
            };
            return finalizeCandidate(candidate, finalStatus, operation, relativePath,
                    "managed memory file updated");
        } catch (Exception e) {
            log.error("处理 memory candidate 失败: candidateId={}", candidate.getId(), e);
            return finalizeCandidate(candidate, MemoryCandidate.CandidateStatus.FAILED,
                    MemoryCandidate.CandidateOperation.SKIP, candidate.getTargetFilePath(),
                    "processing failed: " + e.getMessage());
        }
    }

    private ProcessResult finalizeCandidate(MemoryCandidate candidate,
                                            MemoryCandidate.CandidateStatus status,
                                            MemoryCandidate.CandidateOperation operation,
                                            String targetFilePath,
                                            String notes) {
        candidate.setStatus(status);
        candidate.setProposedOperation(operation);
        candidate.setTargetFilePath(targetFilePath);
        candidate.setMaintenanceNotes(notes);
        candidate.setProcessedAt(new Date());
        candidate.setUpdatedAt(new Date());
        memoryCandidateService.save(candidate);
        return new ProcessResult(status, status == MemoryCandidate.CandidateStatus.PROMOTED
                || status == MemoryCandidate.CandidateStatus.MERGED
                || status == MemoryCandidate.CandidateStatus.CORRECTED
                || status == MemoryCandidate.CandidateStatus.FORGOTTEN);
    }

    private Path resolveTargetPath(MemoryCandidate candidate) {
        Path basePath = Paths.get(memoryFilesPath).resolve("managed");
        return basePath.resolve(candidate.getDedupeKey() + ".md");
    }

    private String normalizeMemoryPath(Path path) {
        Path basePath = Paths.get(memoryFilesPath);
        return basePath.relativize(path).toString().replace('\\', '/');
    }

    private MemoryCandidate.CandidateOperation resolveUpdateOperation(String existingMemoryId, String currentMemoryId) {
        if (existingMemoryId != null && existingMemoryId.equals(currentMemoryId)) {
            return MemoryCandidate.CandidateOperation.CORRECT;
        }
        return MemoryCandidate.CandidateOperation.MERGE;
    }

    private boolean containsForgetSignal(String text) {
        String normalized = normalize(text);
        if (normalized == null) {
            return false;
        }
        return FORGET_SIGNALS.stream().anyMatch(normalized::contains);
    }

    private String buildDurableMemoryContent(MemoryCandidate candidate, MemoryCandidate.CandidateOperation operation) {
        String summary = normalize(candidate.getSummaryContent());
        if (summary == null) {
            return fallbackDurableContent(candidate);
        }
        try {
            List<ChatMessage> messages = List.of(
                    new SystemMessage(systemPromptConfig.getMemoryMaintenanceAgentPrompt()),
                    new UserMessage("""
                            请把下面的 rolling summary 整理成 durable memory。

                            memoryId: %s
                            topic: %s
                            operation: %s
                            summaryType: %s

                            rolling summary:
                            %s
                            """.formatted(
                            safe(candidate.getMemoryId()),
                            safe(candidate.getTopic()),
                            operation.name(),
                            safe(candidate.getSummaryType()),
                            summary
                    ))
            );
            ChatResponse response = chatLanguageModel.chat(messages);
            String content = normalize(response == null || response.aiMessage() == null ? null : response.aiMessage().text());
            return content == null ? fallbackDurableContent(candidate) : content;
        } catch (Exception e) {
            log.warn("LLM durable memory 整理失败，退回规则化输出: candidateId={}", candidate.getId(), e);
            return fallbackDurableContent(candidate);
        }
    }

    private String fallbackDurableContent(MemoryCandidate candidate) {
        return """
                ## 稳定事实
                - topic: %s
                - summaryType: %s
                - memoryId: %s

                ## 约束与偏好
                - 当前由规则化 fallback 生成，后续可继续被 MemoryMaintenanceAgent 修正。

                ## 可复用诊断结论
                %s

                ## 待确认项
                - 若该结论已经过期或被新证据推翻，下一版 candidate 需要触发修正或遗忘。
                """.formatted(
                safe(candidate.getTopic()),
                safe(candidate.getSummaryType()),
                safe(candidate.getMemoryId()),
                safe(candidate.getCandidateContent())
        ).trim();
    }

    private void writeManagedMemoryFile(Path path,
                                        MemoryCandidate candidate,
                                        MemoryCandidate.CandidateOperation operation,
                                        String status,
                                        String durableContent,
                                        String summaryContent) throws IOException {
        Files.createDirectories(path.getParent());
        String markdown = """
                ---
                managed_by: MemoryMaintenanceAgent
                dedupe_key: %s
                memory_id: %s
                session_id: %s
                topic: %s
                summary_type: %s
                candidate_id: %s
                source_summary_version: %s
                status: %s
                content_hash: %s
                last_operation: %s
                updated_at: %s
                ---

                # Managed Memory

                ## Durable Memory

                %s

                ## Source Rolling Summary

                %s

                ## Governance

                - candidateId: %s
                - operation: %s
                - status: %s
                - updatedAt: %s
                - notes: 该文档由 MemoryMaintenanceAgent 从 memory_summaries/candidates 晋升生成。
                """.formatted(
                safe(candidate.getDedupeKey()),
                safe(candidate.getMemoryId()),
                safe(candidate.getSessionId()),
                safe(candidate.getTopic()),
                safe(candidate.getSummaryType()),
                safe(candidate.getId()),
                candidate.getSourceSummaryVersion() == null ? "" : candidate.getSourceSummaryVersion().toString(),
                safe(status),
                safe(candidate.getContentHash()),
                operation.name(),
                TIME_FORMATTER.format(Instant.now()),
                safe(durableContent),
                safe(summaryContent),
                safe(candidate.getId()),
                operation.name(),
                safe(status),
                TIME_FORMATTER.format(Instant.now())
        );
        managedMemoryGovernanceService.recordManagedFileWrite(
                path,
                candidate,
                operation,
                status,
                markdown,
                "maintenance-agent",
                "managed memory file updated by MemoryMaintenanceAgent"
        );
    }

    private Map<String, String> readFrontMatter(Path path) {
        try {
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty() || !"---".equals(lines.get(0).trim())) {
                return Map.of();
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if ("---".equals(line.trim())) {
                    break;
                }
                int delimiter = line.indexOf(':');
                if (delimiter <= 0) {
                    continue;
                }
                String key = line.substring(0, delimiter).trim();
                String value = line.substring(delimiter + 1).trim();
                values.put(key, value);
            }
            return values;
        } catch (Exception e) {
            log.warn("读取 memory file front matter 失败: {}", path, e);
            return Map.of();
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record MaintenanceRunSummary(
            String trigger,
            String startedAt,
            String finishedAt,
            int processedCount,
            int promotedCount,
            int mergedCount,
            int correctedCount,
            int forgottenCount,
            int rejectedCount,
            int failedCount,
            List<String> handledCandidateIds,
            boolean touchedFiles,
            String message
    ) {
        public static MaintenanceRunSummary idle() {
            return new MaintenanceRunSummary(
                    "idle",
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    false,
                    "memory maintenance agent has not run yet"
            );
        }
    }

    private record ProcessResult(MemoryCandidate.CandidateStatus status, boolean touchedFiles) {
    }
}
