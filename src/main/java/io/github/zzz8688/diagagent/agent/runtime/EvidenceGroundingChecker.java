package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.agent.AgentDiagnosisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class EvidenceGroundingChecker {

    private static final int DEFAULT_REPLAY_WINDOW_SIZE = 40;

    private final TaskManager taskManager;
    private final EventReplayProjector eventReplayProjector;

    public GroundingResult check(FinalAnswerContract contract,
                                 MainAgentPromptAssembler.MainAgentPromptContext promptContext,
                                 List<AgentDiagnosisResponse> responses) {
        List<String> issues = new ArrayList<>();
        if (contract == null) {
            issues.add("final answer contract is missing");
            return new GroundingResult(false, List.copyOf(issues));
        }

        EventReplayProjector.EventReplayProjection projection = loadProjection(promptContext);
        Map<String, String> actionTypesByActionId = new LinkedHashMap<>();
        Map<String, String> actionTargetsByActionId = new LinkedHashMap<>();
        projection.eventEntries().forEach(entry -> {
            actionTypesByActionId.put(safe(entry.actionId()), safe(entry.actionType()));
            actionTargetsByActionId.put(safe(entry.actionId()), safe(entry.target()));
        });
        projection.logEntries().forEach(entry -> {
            actionTypesByActionId.put(safe(entry.actionId()), safe(entry.actionType()));
            actionTargetsByActionId.put(safe(entry.actionId()), safe(entry.target()));
        });
        Set<String> eventActionIds = projection.eventEntries().stream()
                .map(EventReplayProjector.EventReplayEntry::actionId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        eventActionIds.addAll(projection.logEntries().stream()
                .map(EventReplayProjector.EventReplayEntry::actionId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet()));
        Set<String> approvalActionIds = projection.approvalEntries().stream()
                .map(EventReplayProjector.EventReplayEntry::actionId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        Set<String> specialistIds = responses == null ? Set.of() : responses.stream()
                .map(AgentDiagnosisResponse::getAgent)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        int groundedCount = 0;
        for (FinalAnswerContract.EvidenceItem item : contract.evidence()) {
            if (item == null) {
                continue;
            }
            String sourceType = safe(item.sourceType()).toUpperCase(Locale.ROOT);
            String sourceId = safe(item.sourceId());
            boolean grounded = switch (sourceType) {
                case "EVENT_REPLAY" -> eventActionIds.contains(sourceId)
                        && ObservationSemantics.isFactualAction(
                        actionTypesByActionId.get(sourceId),
                        actionTargetsByActionId.get(sourceId)
                );
                case "APPROVAL_REPLAY" -> approvalActionIds.contains(sourceId);
                case "SPECIALIST_OBSERVATION" -> specialistIds.contains(sourceId.toLowerCase(Locale.ROOT));
                default -> false;
            };
            if (grounded) {
                groundedCount++;
            } else {
                if ("EVENT_REPLAY".equals(sourceType) && eventActionIds.contains(sourceId)) {
                    issues.add("event replay reference is not factual evidence: " + safe(actionTypesByActionId.get(sourceId)) + "/" + sourceId);
                    continue;
                }
                issues.add("ungrounded evidence reference: " + sourceType + "/" + sourceId);
            }
        }

        if (groundedCount == 0) {
            issues.add("no grounded evidence reference found");
        }
        return new GroundingResult(issues.isEmpty(), List.copyOf(issues));
    }

    public FinalAnswerContract repairContractEvidence(FinalAnswerContract contract,
                                                      MainAgentPromptAssembler.MainAgentPromptContext promptContext,
                                                      List<AgentDiagnosisResponse> responses) {
        if (contract == null) {
            return null;
        }
        EventReplayProjector.EventReplayProjection projection = loadProjection(promptContext);
        GroundingContext groundingContext = buildGroundingContext(projection, responses);
        List<FinalAnswerContract.EvidenceItem> repairedEvidence = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        if (contract.evidence() != null) {
            for (FinalAnswerContract.EvidenceItem item : contract.evidence()) {
                if (isGrounded(item, groundingContext)) {
                    repairedEvidence.add(fillMissingSummary(item, groundingContext));
                    seen.add(identity(item));
                }
            }
        }
        if (repairedEvidence.isEmpty()) {
            for (FinalAnswerContract.EvidenceItem candidate : groundingContext.candidates()) {
                if (seen.add(identity(candidate))) {
                    repairedEvidence.add(candidate);
                }
                if (repairedEvidence.size() >= 2) {
                    break;
                }
            }
        }
        if (repairedEvidence.isEmpty()) {
            return contract;
        }
        if (sameEvidence(contract.evidence(), repairedEvidence)) {
            return contract;
        }
        return new FinalAnswerContract(
                contract.summary(),
                List.copyOf(repairedEvidence),
                contract.rootCause(),
                contract.nextActions(),
                contract.confidence(),
                contract.needsHandoff()
        );
    }

    private EventReplayProjector.EventReplayProjection loadProjection(MainAgentPromptAssembler.MainAgentPromptContext promptContext) {
        if (promptContext == null || promptContext.taskId() == null || promptContext.taskId().isBlank()) {
            return EventReplayProjector.EventReplayProjection.empty();
        }
        List<ExecutionEvent> events = taskManager.replayWindow(promptContext.taskId(), 0, DEFAULT_REPLAY_WINDOW_SIZE);
        if (events.isEmpty()) {
            return EventReplayProjector.EventReplayProjection.empty();
        }
        return eventReplayProjector.project(events);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private GroundingContext buildGroundingContext(EventReplayProjector.EventReplayProjection projection,
                                                   List<AgentDiagnosisResponse> responses) {
        Map<String, String> actionTypesByActionId = new LinkedHashMap<>();
        Map<String, String> actionTargetsByActionId = new LinkedHashMap<>();
        Map<String, String> summariesByReference = new LinkedHashMap<>();
        projection.eventEntries().forEach(entry -> {
            actionTypesByActionId.put(safe(entry.actionId()), safe(entry.actionType()));
            actionTargetsByActionId.put(safe(entry.actionId()), safe(entry.target()));
            summariesByReference.put("EVENT_REPLAY/" + safe(entry.actionId()), safe(entry.summary()));
        });
        projection.logEntries().forEach(entry -> {
            actionTypesByActionId.put(safe(entry.actionId()), safe(entry.actionType()));
            actionTargetsByActionId.put(safe(entry.actionId()), safe(entry.target()));
            summariesByReference.put("EVENT_REPLAY/" + safe(entry.actionId()), safe(entry.summary()));
        });
        Set<String> eventActionIds = projection.eventEntries().stream()
                .map(EventReplayProjector.EventReplayEntry::actionId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        eventActionIds.addAll(projection.logEntries().stream()
                .map(EventReplayProjector.EventReplayEntry::actionId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet()));
        Set<String> approvalActionIds = projection.approvalEntries().stream()
                .map(EventReplayProjector.EventReplayEntry::actionId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        Set<String> specialistIds = responses == null ? Set.of() : responses.stream()
                .map(AgentDiagnosisResponse::getAgent)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (responses != null) {
            for (AgentDiagnosisResponse response : responses) {
                if (response == null || response.getAgent() == null || response.getAgent().isBlank()) {
                    continue;
                }
                summariesByReference.put(
                        "SPECIALIST_OBSERVATION/" + response.getAgent().trim(),
                        firstNonBlank(response.getConclusion(), response.getAnalysis())
                );
            }
        }
        List<FinalAnswerContract.EvidenceItem> candidates = new ArrayList<>();
        projection.eventEntries().stream()
                .filter(entry -> ObservationSemantics.isFactualAction(entry.actionType(), entry.target()))
                .forEach(entry -> candidates.add(new FinalAnswerContract.EvidenceItem(
                        "EVENT_REPLAY",
                        safe(entry.actionId()),
                        safe(entry.summary())
                )));
        projection.logEntries().stream()
                .filter(entry -> ObservationSemantics.isFactualAction(entry.actionType(), entry.target()))
                .forEach(entry -> candidates.add(new FinalAnswerContract.EvidenceItem(
                        "EVENT_REPLAY",
                        safe(entry.actionId()),
                        safe(entry.summary())
                )));
        return new GroundingContext(
                actionTypesByActionId,
                actionTargetsByActionId,
                eventActionIds,
                approvalActionIds,
                specialistIds,
                summariesByReference,
                List.copyOf(candidates)
        );
    }

    private boolean isGrounded(FinalAnswerContract.EvidenceItem item, GroundingContext groundingContext) {
        if (item == null || groundingContext == null) {
            return false;
        }
        String sourceType = safe(item.sourceType()).toUpperCase(Locale.ROOT);
        String sourceId = safe(item.sourceId());
        return switch (sourceType) {
            case "EVENT_REPLAY" -> groundingContext.eventActionIds().contains(sourceId)
                    && ObservationSemantics.isFactualAction(
                    groundingContext.actionTypesByActionId().get(sourceId),
                    groundingContext.actionTargetsByActionId().get(sourceId)
            );
            case "APPROVAL_REPLAY" -> groundingContext.approvalActionIds().contains(sourceId);
            case "SPECIALIST_OBSERVATION" -> groundingContext.specialistIds().contains(sourceId.toLowerCase(Locale.ROOT));
            default -> false;
        };
    }

    private FinalAnswerContract.EvidenceItem fillMissingSummary(FinalAnswerContract.EvidenceItem item,
                                                                GroundingContext groundingContext) {
        if (item == null) {
            return null;
        }
        String summary = safe(item.summary());
        if (!summary.isBlank()) {
            return item;
        }
        String repairedSummary = groundingContext.summariesByReference()
                .getOrDefault(safe(item.sourceType()).toUpperCase(Locale.ROOT) + "/" + safe(item.sourceId()), "");
        if (repairedSummary.isBlank()) {
            return item;
        }
        return new FinalAnswerContract.EvidenceItem(item.sourceType(), item.sourceId(), repairedSummary);
    }

    private boolean sameEvidence(List<FinalAnswerContract.EvidenceItem> left,
                                 List<FinalAnswerContract.EvidenceItem> right) {
        if (left == null || left.isEmpty()) {
            return right == null || right.isEmpty();
        }
        if (right == null || left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            FinalAnswerContract.EvidenceItem leftItem = left.get(index);
            FinalAnswerContract.EvidenceItem rightItem = right.get(index);
            if (!identity(leftItem).equals(identity(rightItem))) {
                return false;
            }
            if (!safe(leftItem == null ? null : leftItem.summary()).equals(safe(rightItem == null ? null : rightItem.summary()))) {
                return false;
            }
        }
        return true;
    }

    private String identity(FinalAnswerContract.EvidenceItem item) {
        if (item == null) {
            return "";
        }
        return safe(item.sourceType()).toUpperCase(Locale.ROOT) + "/" + safe(item.sourceId());
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return safe(second);
    }

    private record GroundingContext(
            Map<String, String> actionTypesByActionId,
            Map<String, String> actionTargetsByActionId,
            Set<String> eventActionIds,
            Set<String> approvalActionIds,
            Set<String> specialistIds,
            Map<String, String> summariesByReference,
            List<FinalAnswerContract.EvidenceItem> candidates
    ) {
    }

    public record GroundingResult(
            boolean grounded,
            List<String> issues
    ) {
    }
}
