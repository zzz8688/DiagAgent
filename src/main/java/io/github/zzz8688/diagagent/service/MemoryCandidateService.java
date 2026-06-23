package io.github.zzz8688.diagagent.service;

import io.github.zzz8688.diagagent.entity.MemoryCandidate;
import io.github.zzz8688.diagagent.repository.MemoryCandidateRepository;
import io.github.zzz8688.diagagent.store.MemorySummaryDoc;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MemoryCandidateService {

    private static final int MAX_CANDIDATE_LENGTH = 4000;
    private static final List<String> AGENT_SUFFIXES = List.of("-app", "-db", "-network", "-legacy");

    private final MemoryCandidateRepository memoryCandidateRepository;

    public MemoryCandidate createOrUpdateFromSummary(MemorySummaryDoc summaryDoc) {
        if (summaryDoc == null || summaryDoc.getMemoryId() == null || summaryDoc.getMemoryId().isBlank()) {
            return null;
        }
        Integer sourceSummaryVersion = summaryDoc.getVersion() == null ? 0 : summaryDoc.getVersion();
        Optional<MemoryCandidate> existing = memoryCandidateRepository.findByMemoryIdAndSourceSummaryVersion(
                summaryDoc.getMemoryId(),
                sourceSummaryVersion
        );
        MemoryCandidate candidate = existing.orElseGet(MemoryCandidate::new);
        Date now = new Date();
        if (candidate.getCreatedAt() == null) {
            candidate.setCreatedAt(now);
        }
        candidate.setUpdatedAt(now);
        candidate.setMemoryId(summaryDoc.getMemoryId());
        candidate.setSessionId(resolveSessionId(summaryDoc.getMemoryId()));
        candidate.setTopic(normalize(summaryDoc.getTopic()));
        candidate.setSummaryType(normalize(summaryDoc.getSummaryType()));
        candidate.setSourceSummaryVersion(sourceSummaryVersion);
        candidate.setSummaryContent(summaryDoc.getContent());
        candidate.setCandidateContent(compactCandidateContent(summaryDoc.getContent()));
        candidate.setDedupeKey(buildDedupeKey(summaryDoc));
        candidate.setContentHash(hash(summaryDoc.getContent()));
        if (candidate.getStatus() == null || isTerminal(candidate.getStatus())) {
            candidate.setStatus(MemoryCandidate.CandidateStatus.PENDING);
        }
        if (candidate.getProposedOperation() == null) {
            candidate.setProposedOperation(MemoryCandidate.CandidateOperation.CREATE);
        }
        candidate.setMetadata(buildMetadata(summaryDoc));
        return memoryCandidateRepository.save(candidate);
    }

    public List<MemoryCandidate> listRecentCandidates(int limit) {
        List<MemoryCandidate> candidates = memoryCandidateRepository.findTop50ByOrderByUpdatedAtDesc();
        if (limit <= 0 || candidates.size() <= limit) {
            return candidates;
        }
        return candidates.subList(0, limit);
    }

    public List<MemoryCandidate> listPendingCandidates(int limit) {
        List<MemoryCandidate> candidates = memoryCandidateRepository.findTop50ByStatusOrderByUpdatedAtAsc(
                MemoryCandidate.CandidateStatus.PENDING
        );
        if (limit <= 0 || candidates.size() <= limit) {
            return candidates;
        }
        return candidates.subList(0, limit);
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (MemoryCandidate.CandidateStatus status : MemoryCandidate.CandidateStatus.values()) {
            counts.put(status.name(), memoryCandidateRepository.countByStatus(status));
        }
        return counts;
    }

    public MemoryCandidate save(MemoryCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        candidate.setUpdatedAt(new Date());
        return memoryCandidateRepository.save(candidate);
    }

    public boolean isTerminal(MemoryCandidate.CandidateStatus status) {
        return status == MemoryCandidate.CandidateStatus.PROMOTED
                || status == MemoryCandidate.CandidateStatus.MERGED
                || status == MemoryCandidate.CandidateStatus.CORRECTED
                || status == MemoryCandidate.CandidateStatus.FORGOTTEN
                || status == MemoryCandidate.CandidateStatus.REJECTED
                || status == MemoryCandidate.CandidateStatus.FAILED;
    }

    private Map<String, Object> buildMetadata(MemorySummaryDoc summaryDoc) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (summaryDoc.getMetadata() != null) {
            metadata.putAll(summaryDoc.getMetadata());
        }
        if (summaryDoc.getSourceMessageRange() != null) {
            metadata.put("sourceMessageRangeStart", summaryDoc.getSourceMessageRange().getStartMessageIndex());
            metadata.put("sourceMessageRangeEnd", summaryDoc.getSourceMessageRange().getEndMessageIndex());
            metadata.put("sourceQuestionStart", summaryDoc.getSourceMessageRange().getStartUserQuestionCount());
            metadata.put("sourceQuestionEnd", summaryDoc.getSourceMessageRange().getEndUserQuestionCount());
        }
        metadata.put("memoryId", summaryDoc.getMemoryId());
        metadata.put("summaryType", summaryDoc.getSummaryType());
        metadata.put("topic", summaryDoc.getTopic());
        metadata.put("version", summaryDoc.getVersion());
        return metadata;
    }

    private String resolveSessionId(String memoryId) {
        String normalized = normalize(memoryId);
        if (normalized == null) {
            return "";
        }
        for (String suffix : AGENT_SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                return normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        return normalized;
    }

    private String buildDedupeKey(MemorySummaryDoc summaryDoc) {
        String topic = sanitizeKey(summaryDoc.getTopic());
        if (topic.length() >= 8 && !topic.startsWith("memory-")) {
            return topic;
        }
        return sanitizeKey(summaryDoc.getMemoryId());
    }

    private String sanitizeKey(String value) {
        String normalized = normalize(value);
        if (normalized == null || normalized.isBlank()) {
            return "memory";
        }
        String key = normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        return key.isBlank() ? "memory" : key;
    }

    private String compactCandidateContent(String content) {
        String normalized = normalize(content);
        if (normalized == null) {
            return "";
        }
        return normalized.length() <= MAX_CANDIDATE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_CANDIDATE_LENGTH);
    }

    private String hash(String content) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            byte[] digest = messageDigest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return Integer.toHexString((content == null ? "" : content).hashCode());
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
