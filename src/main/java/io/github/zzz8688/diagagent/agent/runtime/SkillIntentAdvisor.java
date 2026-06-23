package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.skills.SkillSearchResult;
import io.github.zzz8688.diagagent.skills.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
class SkillIntentAdvisor {

    private static final int MAX_SKILL_CANDIDATES = 3;
    private static final double MIN_RECOMMEND_SCORE = 2.5D;
    private static final double RELATIVE_RECOMMEND_RATIO = 0.72D;
    private static final double FORCE_SKILL_FIRST_SCORE = 4.5D;

    private final SkillService skillService;

    List<String> recommendSkillIds(String userQuestion, String evidenceSummary) {
        List<SkillSearchResult> matches = matchSkills(userQuestion, evidenceSummary);
        if (matches.isEmpty()) {
            return List.of();
        }
        double topScore = matches.get(0).score();
        return matches.stream()
                .filter(result -> isRecommendable(result, topScore))
                .map(result -> result.skill().id())
                .collect(Collectors.toList());
    }

    boolean shouldForceSkillFirst(String userQuestion) {
        return topSkillMatch(userQuestion)
                .map(result -> result.score() >= FORCE_SKILL_FIRST_SCORE)
                .orElse(false);
    }

    String firstAvailableSkill(CapabilityViewAssembler.CapabilityView capabilityView,
                               String userQuestion,
                               String evidenceSummary) {
        if (capabilityView == null || capabilityView.skills() == null || capabilityView.skills().isEmpty()) {
            return null;
        }
        List<String> recommendedIds = recommendSkillIds(userQuestion, evidenceSummary);
        for (String skillId : recommendedIds) {
            boolean exists = capabilityView.skills().stream()
                    .anyMatch(entry -> entry != null && skillId.equalsIgnoreCase(entry.id()));
            if (exists) {
                return skillId;
            }
        }
        return null;
    }

    private List<SkillSearchResult> matchSkills(String userQuestion, String evidenceSummary) {
        Map<String, SkillSearchResult> merged = new LinkedHashMap<>();
        mergeMatches(merged, skillService.search(safe(userQuestion), MAX_SKILL_CANDIDATES));
        String combined = joinNonBlank(userQuestion, evidenceSummary);
        if (!combined.isBlank()) {
            mergeMatches(merged, skillService.search(combined, MAX_SKILL_CANDIDATES));
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(SkillSearchResult::score).reversed())
                .limit(MAX_SKILL_CANDIDATES)
                .collect(Collectors.toList());
    }

    private java.util.Optional<SkillSearchResult> topSkillMatch(String userQuestion) {
        return skillService.search(safe(userQuestion), 1).stream().findFirst();
    }

    private void mergeMatches(Map<String, SkillSearchResult> merged, List<SkillSearchResult> matches) {
        if (matches == null) {
            return;
        }
        for (SkillSearchResult match : matches) {
            if (match == null || match.skill() == null || match.skill().id() == null) {
                continue;
            }
            merged.merge(
                    match.skill().id(),
                    match,
                    (left, right) -> left.score() >= right.score() ? left : right
            );
        }
    }

    private boolean isRecommendable(SkillSearchResult result, double topScore) {
        if (result == null) {
            return false;
        }
        return result.score() >= MIN_RECOMMEND_SCORE
                && (topScore <= 0D || result.score() >= topScore * RELATIVE_RECOMMEND_RATIO);
    }

    private String joinNonBlank(String left, String right) {
        if (safe(left).isBlank()) {
            return safe(right);
        }
        if (safe(right).isBlank()) {
            return safe(left);
        }
        return safe(left) + "\n" + safe(right);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
