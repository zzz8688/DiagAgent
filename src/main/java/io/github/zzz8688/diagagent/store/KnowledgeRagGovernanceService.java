package io.github.zzz8688.diagagent.store;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class KnowledgeRagGovernanceService {

    private static final int MAX_RECENT_EVALUATIONS = 8;
    private static final String DEFAULT_BASELINE_SUITE_ID = "frontend-diagnosis-full";
    private static final String DEFAULT_PRESET_ID = "active-config";

    private final KnowledgeBaseService knowledgeBaseService;
    private final AtomicReference<RagEvaluationResult> latestEvaluation = new AtomicReference<>();
    private final AtomicReference<List<RagEvaluationResult>> recentEvaluations = new AtomicReference<>(List.of());

    public KnowledgeRagGovernanceService(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.latestEvaluation.set(RagEvaluationResult.empty(knowledgeBaseService.snapshotConfig()));
    }

    public RagGovernanceView getGovernanceView() {
        return new RagGovernanceView(
                knowledgeBaseService.snapshotConfig(),
                baselineSuites(),
                configPresets(),
                latestEvaluation.get(),
                recentEvaluations.get()
        );
    }

    public RagEvaluationResult evaluate(RagEvaluationRequest request) {
        KnowledgeBaseService.RagRetrievalConfig baseline = knowledgeBaseService.snapshotConfig();
        String presetId = normalizePresetId(request == null ? null : request.presetId());
        String baselineSuiteId = normalizeBaselineSuiteId(request == null ? null : request.baselineSuiteId());
        KnowledgeBaseService.RagRetrievalConfig effectiveConfig = mergeConfig(
                baseline,
                configPresetById().get(presetId),
                request == null ? null : request.configOverride()
        );
        List<RagEvaluationCase> cases = resolveCases(request == null ? null : request.cases(), baselineSuiteId);
        if (cases.isEmpty()) {
            RagEvaluationResult empty = RagEvaluationResult.empty(effectiveConfig);
            latestEvaluation.set(empty);
            return empty;
        }
        String evaluationLabel = resolveEvaluationLabel(request == null ? null : request.label(), baselineSuiteId, presetId);

        List<RagEvaluationCaseResult> caseResults = new ArrayList<>();
        int hitAt1 = 0;
        int hitAt3 = 0;
        double reciprocalRankSum = 0D;
        for (RagEvaluationCase evaluationCase : cases) {
            KnowledgeBaseService.RagSearchReport report = knowledgeBaseService.searchReport(
                    evaluationCase.query(),
                    effectiveConfig.finalTopK(),
                    effectiveConfig
            );
            int matchedRank = resolveMatchedRank(report.results(), evaluationCase.expectedTitles(), evaluationCase.expectedKeywords());
            boolean matchedAt1 = matchedRank == 1;
            boolean matchedAt3 = matchedRank > 0 && matchedRank <= 3;
            if (matchedAt1) {
                hitAt1++;
            }
            if (matchedAt3) {
                hitAt3++;
            }
            reciprocalRankSum += matchedRank > 0 ? 1D / matchedRank : 0D;
            double topScore = report.results().isEmpty() ? 0D : report.results().get(0).finalScore();
            caseResults.add(new RagEvaluationCaseResult(
                    evaluationCase.query(),
                    evaluationCase.expectedTitles(),
                    evaluationCase.expectedKeywords(),
                    evaluationCase.note(),
                    matchedRank,
                    matchedAt1,
                    matchedAt3,
                    topScore,
                    report.vectorCandidateCount(),
                    report.keywordCandidateCount(),
                    toTopResults(report.results())
            ));
        }

        RagEvaluationSummary summary = new RagEvaluationSummary(
                cases.size(),
                hitAt1,
                hitAt3,
                ratio(hitAt1, cases.size()),
                ratio(hitAt3, cases.size()),
                reciprocalRankSum / cases.size()
        );
        RagEvaluationResult result = new RagEvaluationResult(
                "rag-eval-" + System.currentTimeMillis(),
                evaluationLabel,
                Instant.now().toString(),
                baselineSuiteId,
                presetId,
                effectiveConfig,
                summary,
                caseResults
        );
        latestEvaluation.set(result);
        recentEvaluations.set(appendRecentEvaluation(result));
        return result;
    }

    private List<RagEvaluationCase> sanitizeCases(List<RagEvaluationCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return List.of();
        }
        return cases.stream()
                .filter(item -> item != null && item.query() != null && !item.query().isBlank())
                .map(item -> new RagEvaluationCase(
                        item.query().trim(),
                        sanitizeList(item.expectedTitles()),
                        item.expectedKeywords() == null ? List.of() : item.expectedKeywords().stream()
                                .filter(keyword -> keyword != null && !keyword.isBlank())
                                .map(String::trim)
                                .distinct()
                                .toList(),
                        item.note() == null ? "" : item.note().trim()
                ))
                .toList();
    }

    private List<RagEvaluationResult> appendRecentEvaluation(RagEvaluationResult result) {
        List<RagEvaluationResult> merged = new ArrayList<>();
        merged.add(result);
        merged.addAll(recentEvaluations.get().stream()
                .filter(item -> item != null && !item.evaluationId().equals(result.evaluationId()))
                .limit(Math.max(0, MAX_RECENT_EVALUATIONS - 1))
                .toList());
        return List.copyOf(merged);
    }

    private List<RagEvaluationCase> resolveCases(List<RagEvaluationCase> requestCases, String baselineSuiteId) {
        List<RagEvaluationCase> sanitizedRequestCases = sanitizeCases(requestCases);
        if (!sanitizedRequestCases.isEmpty()) {
            return sanitizedRequestCases;
        }
        RagBaselineSuite suite = baselineSuiteById().getOrDefault(baselineSuiteId, baselineSuiteById().get(DEFAULT_BASELINE_SUITE_ID));
        return suite == null ? List.of() : sanitizeCases(suite.cases());
    }

    private String normalizePresetId(String presetId) {
        if (presetId == null || presetId.isBlank()) {
            return DEFAULT_PRESET_ID;
        }
        return configPresetById().containsKey(presetId) ? presetId : DEFAULT_PRESET_ID;
    }

    private String normalizeBaselineSuiteId(String baselineSuiteId) {
        if (baselineSuiteId == null || baselineSuiteId.isBlank()) {
            return DEFAULT_BASELINE_SUITE_ID;
        }
        return baselineSuiteById().containsKey(baselineSuiteId) ? baselineSuiteId : DEFAULT_BASELINE_SUITE_ID;
    }

    private String resolveEvaluationLabel(String requestedLabel, String baselineSuiteId, String presetId) {
        if (requestedLabel != null && !requestedLabel.isBlank()) {
            return requestedLabel.trim();
        }
        RagBaselineSuite suite = baselineSuiteById().get(baselineSuiteId);
        RagConfigPreset preset = configPresetById().get(presetId);
        String suiteLabel = suite == null ? "custom" : suite.displayName();
        String presetLabel = preset == null ? "active" : preset.displayName();
        return suiteLabel + " / " + presetLabel;
    }

    private int resolveMatchedRank(List<KnowledgeSearchResult> results, List<String> expectedTitles, List<String> expectedKeywords) {
        if (results == null || results.isEmpty()) {
            return 0;
        }
        for (int index = 0; index < results.size(); index++) {
            KnowledgeSearchResult result = results.get(index);
            String title = safeMetadata(result, "title");
            String source = safeMetadata(result, "source");
            String content = result.segment() == null || result.segment().text() == null ? "" : result.segment().text();
            if ((expectedTitles != null && expectedTitles.stream().anyMatch(expectedTitle -> containsIgnoreCase(title, expectedTitle)
                    || containsIgnoreCase(source, expectedTitle)))
                    || (expectedKeywords != null && expectedKeywords.stream().anyMatch(keyword -> containsIgnoreCase(title, keyword)
                    || containsIgnoreCase(source, keyword)
                    || containsIgnoreCase(content, keyword)))) {
                return index + 1;
            }
        }
        return 0;
    }

    private List<String> sanitizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<RagTopResult> toTopResults(List<KnowledgeSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
                .map(result -> new RagTopResult(
                        safeMetadata(result, "title"),
                        safeMetadata(result, "source"),
                        result.retrievalMode(),
                        result.finalScore(),
                        result.rerankScore(),
                        result.fusionScore(),
                        result.vectorScore(),
                        result.keywordScore()
                ))
                .toList();
    }

    private KnowledgeBaseService.RagRetrievalConfig mergeConfig(KnowledgeBaseService.RagRetrievalConfig baseline,
                                                                RagConfigPreset preset,
                                                                RagRetrievalConfigOverride override) {
        RagRetrievalConfigOverride presetOverride = preset == null ? null : preset.configOverride();
        return new KnowledgeBaseService.RagRetrievalConfig(
                chooseInt(baseline.vectorTopK(), presetOverride == null ? null : presetOverride.vectorTopK(), override == null ? null : override.vectorTopK()),
                chooseInt(baseline.keywordTopK(), presetOverride == null ? null : presetOverride.keywordTopK(), override == null ? null : override.keywordTopK()),
                chooseInt(baseline.finalTopK(), presetOverride == null ? null : presetOverride.finalTopK(), override == null ? null : override.finalTopK()),
                chooseInt(baseline.fusionTopK(), presetOverride == null ? null : presetOverride.fusionTopK(), override == null ? null : override.fusionTopK()),
                chooseInt(baseline.rerankTopN(), presetOverride == null ? null : presetOverride.rerankTopN(), override == null ? null : override.rerankTopN()),
                chooseDouble(baseline.minVectorScore(), presetOverride == null ? null : presetOverride.minVectorScore(), override == null ? null : override.minVectorScore()),
                baseline.rerankerConfigured()
        );
    }

    private int chooseInt(int baseline, Integer presetValue, Integer overrideValue) {
        if (overrideValue != null) {
            return overrideValue;
        }
        if (presetValue != null) {
            return presetValue;
        }
        return baseline;
    }

    private double chooseDouble(double baseline, Double presetValue, Double overrideValue) {
        if (overrideValue != null) {
            return overrideValue;
        }
        if (presetValue != null) {
            return presetValue;
        }
        return baseline;
    }

    private List<RagBaselineSuite> baselineSuites() {
        return List.of(
                new RagBaselineSuite(
                        DEFAULT_BASELINE_SUITE_ID,
                        "前端诊断全量基线",
                        "聚合诊断页 6 个默认问题，适合作为求职展示和回归评测的默认口径。",
                        List.of(
                                new RagEvaluationCase(
                                        "结账失败了",
                                        List.of("支付网关抖动", "支付网关超时故障"),
                                        List.of("支付", "网关", "504"),
                                        "默认问题 1；应能召回支付网关超时、上游抖动和重试耗尽相关知识。"
                                ),
                                new RagEvaluationCase(
                                        "支付一直转圈",
                                        List.of("支付网关抖动"),
                                        List.of("支付", "重试", "转圈"),
                                        "默认问题 2；应能召回支付轮询等待、网关回执延迟和重试控制相关知识。"
                                ),
                                new RagEvaluationCase(
                                        "商品页面打开很慢",
                                        List.of("商品页慢但非数据库"),
                                        List.of("商品", "首屏", "缓存"),
                                        "默认问题 3；应能召回商品页首屏阻塞、缓存未命中和下游依赖慢相关知识。"
                                ),
                                new RagEvaluationCase(
                                        "系统好像有点异常",
                                        List.of("健康巡检型异常", "服务健康检查"),
                                        List.of("健康", "巡检", "实例"),
                                        "默认问题 4；应能召回健康巡检、实例异常和服务健康评分相关知识。"
                                ),
                                new RagEvaluationCase(
                                        "页面提示数据库连接超时",
                                        List.of("数据库连接超时故障"),
                                        List.of("数据库", "连接", "超时"),
                                        "默认问题 5；应能召回数据库连接池、连接等待和超时治理相关知识，对齐连接池耗尽证据链。"
                                ),
                                new RagEvaluationCase(
                                        "凌晨偶尔会有订单失败",
                                        List.of("上游依赖错误与波动"),
                                        List.of("订单", "偶发", "摇摆"),
                                        "默认问题 6；应能召回凌晨窗口偶发失败、跨服务摇摆和依赖波动相关知识。"
                                )
                        )
                ),
                new RagBaselineSuite(
                        "frontend-diagnosis-sample-1",
                        "前端页面样例 1",
                        "对应诊断页前 3 个默认问题：结账失败、支付一直转圈、商品页面打开很慢。",
                        List.of(
                                new RagEvaluationCase(
                                        "结账失败了",
                                        List.of("支付网关抖动", "支付网关超时故障"),
                                        List.of("支付", "网关", "504"),
                                        "默认问题 1；应能召回支付网关超时、上游抖动和重试耗尽相关知识。"
                                ),
                                new RagEvaluationCase(
                                        "支付一直转圈",
                                        List.of("支付网关抖动"),
                                        List.of("支付", "重试", "转圈"),
                                        "默认问题 2；应能召回支付轮询等待、网关回执延迟和重试控制相关知识。"
                                ),
                                new RagEvaluationCase(
                                        "商品页面打开很慢",
                                        List.of("商品页慢但非数据库"),
                                        List.of("商品", "首屏", "缓存"),
                                        "默认问题 3；应能召回商品页首屏阻塞、缓存未命中和下游依赖慢相关知识。"
                                )
                        )
                ),
                new RagBaselineSuite(
                        "frontend-diagnosis-sample-2",
                        "前端页面样例 2",
                        "对应诊断页后 3 个默认问题：系统异常、数据库连接超时、凌晨偶发订单失败。",
                        List.of(
                                new RagEvaluationCase(
                                        "系统好像有点异常",
                                        List.of("健康巡检型异常", "服务健康检查"),
                                        List.of("健康", "巡检", "实例"),
                                        "默认问题 4；应能召回健康巡检、实例异常和服务健康评分相关知识。"
                                ),
                                new RagEvaluationCase(
                                        "页面提示数据库连接超时",
                                        List.of("数据库连接超时故障"),
                                        List.of("数据库", "连接", "超时"),
                                        "默认问题 5；应能召回数据库连接池、连接等待和超时治理相关知识，对齐连接池耗尽证据链。"
                                ),
                                new RagEvaluationCase(
                                        "凌晨偶尔会有订单失败",
                                        List.of("上游依赖错误与波动"),
                                        List.of("订单", "偶发", "摇摆"),
                                        "默认问题 6；应能召回凌晨窗口偶发失败、跨服务摇摆和依赖波动相关知识。"
                                )
                        )
                )
        );
    }

    private Map<String, RagBaselineSuite> baselineSuiteById() {
        return baselineSuites().stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.id(), item), Map::putAll);
    }

    private List<RagConfigPreset> configPresets() {
        KnowledgeBaseService.RagRetrievalConfig active = knowledgeBaseService.snapshotConfig();
        return List.of(
                new RagConfigPreset(
                        DEFAULT_PRESET_ID,
                        "Active Config",
                        "直接使用当前后端生效的检索参数，适合做回归基线。",
                        new RagRetrievalConfigOverride(
                                active.vectorTopK(),
                                active.keywordTopK(),
                                active.finalTopK(),
                                active.fusionTopK(),
                                active.rerankTopN(),
                                active.minVectorScore()
                        )
                ),
                new RagConfigPreset(
                        "balanced-rerank",
                        "Balanced Rerank",
                        "适中扩大融合候选和重排范围，适合默认比较 reranker 质量。",
                        new RagRetrievalConfigOverride(18, 18, 5, 28, 10, 0.18)
                ),
                new RagConfigPreset(
                        "keyword-recall",
                        "Keyword Recall",
                        "提高关键词候选占比，适合验证故障名词和错误码类命中。",
                        new RagRetrievalConfigOverride(12, 24, 5, 30, 8, 0.15)
                ),
                new RagConfigPreset(
                        "vector-precision",
                        "Vector Precision",
                        "提高向量过滤阈值，适合验证语义召回精度。",
                        new RagRetrievalConfigOverride(20, 10, 5, 24, 8, 0.28)
                )
        );
    }

    private Map<String, RagConfigPreset> configPresetById() {
        return configPresets().stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.id(), item), Map::putAll);
    }

    private double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0D;
        }
        return (double) numerator / denominator;
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        if (text == null || keyword == null) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private String safeMetadata(KnowledgeSearchResult result, String key) {
        if (result == null || result.segment() == null || result.segment().metadata() == null) {
            return "";
        }
        String value = result.segment().metadata().getString(key);
        return value == null ? "" : value;
    }

    public record RagGovernanceView(
            KnowledgeBaseService.RagRetrievalConfig activeConfig,
            List<RagBaselineSuite> baselineSuites,
            List<RagConfigPreset> configPresets,
            RagEvaluationResult latestEvaluation,
            List<RagEvaluationResult> recentEvaluations
    ) {
    }

    public record RagEvaluationRequest(
            List<RagEvaluationCase> cases,
            RagRetrievalConfigOverride configOverride,
            String label,
            String baselineSuiteId,
            String presetId
    ) {
    }

    public record RagRetrievalConfigOverride(
            Integer vectorTopK,
            Integer keywordTopK,
            Integer finalTopK,
            Integer fusionTopK,
            Integer rerankTopN,
            Double minVectorScore
    ) {
    }

    public record RagEvaluationCase(
            String query,
            List<String> expectedTitles,
            List<String> expectedKeywords,
            String note
    ) {
    }

    public record RagEvaluationResult(
            String evaluationId,
            String evaluationLabel,
            String evaluatedAt,
            String baselineSuiteId,
            String presetId,
            KnowledgeBaseService.RagRetrievalConfig effectiveConfig,
            RagEvaluationSummary summary,
            List<RagEvaluationCaseResult> cases
    ) {
        public static RagEvaluationResult empty(KnowledgeBaseService.RagRetrievalConfig config) {
            return new RagEvaluationResult(
                    "",
                    "",
                    "",
                    "",
                    "",
                    config,
                    new RagEvaluationSummary(0, 0, 0, 0D, 0D, 0D),
                    List.of()
            );
        }
    }

    public record RagEvaluationSummary(
            int totalCases,
            int hitAt1Count,
            int hitAt3Count,
            double hitAt1Ratio,
            double hitAt3Ratio,
            double meanReciprocalRank
    ) {
    }

    public record RagEvaluationCaseResult(
            String query,
            List<String> expectedTitles,
            List<String> expectedKeywords,
            String note,
            int matchedRank,
            boolean hitAt1,
            boolean hitAt3,
            double topScore,
            int vectorCandidateCount,
            int keywordCandidateCount,
            List<RagTopResult> topResults
    ) {
    }

    public record RagTopResult(
            String title,
            String source,
            String retrievalMode,
            double finalScore,
            double rerankScore,
            double fusionScore,
            double vectorScore,
            double keywordScore
    ) {
    }

    public record RagBaselineSuite(
            String id,
            String displayName,
            String description,
            List<RagEvaluationCase> cases
    ) {
    }

    public record RagConfigPreset(
            String id,
            String displayName,
            String description,
            RagRetrievalConfigOverride configOverride
    ) {
    }
}
