package io.github.zzz8688.diagagent.service;

import io.github.zzz8688.diagagent.config.MemoryFileRagConfig;
import io.github.zzz8688.diagagent.entity.ManagedMemoryMutation;
import io.github.zzz8688.diagagent.entity.ManagedMemoryVersion;
import io.github.zzz8688.diagagent.entity.MemoryCandidate;
import io.github.zzz8688.diagagent.repository.ManagedMemoryMutationRepository;
import io.github.zzz8688.diagagent.repository.ManagedMemoryVersionRepository;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionService;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxProtectedActionEnvelope;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ManagedMemoryGovernanceService {

    private final ManagedMemoryVersionRepository managedMemoryVersionRepository;
    private final ManagedMemoryMutationRepository managedMemoryMutationRepository;
    private final MemoryFileRagConfig memoryFileRagConfig;
    private final SandboxExecutionService sandboxExecutionService;

    @Value("${memory.files.path:./memory}")
    private String memoryFilesPath;

    public WriteResult recordManagedFileWrite(Path path,
                                              MemoryCandidate candidate,
                                              MemoryCandidate.CandidateOperation operation,
                                              String status,
                                              String markdown,
                                              String trigger,
                                              String summary) throws IOException {
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path basePath = Paths.get(memoryFilesPath).toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(basePath)) {
            throw new IOException("managed memory file path is outside memory base path");
        }
        String memoryPath = basePath.relativize(normalizedPath).toString().replace('\\', '/');
        SandboxProtectedActionEnvelope<WriteResult> envelope = sandboxExecutionService.executeProtectedAction(
                "managedMemoryWrite",
                SandboxTargetType.FILE,
                memoryPath,
                buildWriteArguments(memoryPath, candidate, operation, status, trigger, summary),
                basePath.toString(),
                () -> persistManagedFileWrite(normalizedPath, memoryPath, candidate, operation, status, markdown, trigger, summary)
        );
        if (!envelope.success() || envelope.result() == null) {
            throw new IOException("managed memory write blocked by sandbox: " + safe(envelope.errorMessage()));
        }
        return envelope.result();
    }

    private WriteResult persistManagedFileWrite(Path normalizedPath,
                                                String memoryPath,
                                                MemoryCandidate candidate,
                                                MemoryCandidate.CandidateOperation operation,
                                                String status,
                                                String markdown,
                                                String trigger,
                                                String summary) throws IOException {
        Files.createDirectories(normalizedPath.getParent());
        String previousContent = Files.exists(normalizedPath) ? Files.readString(normalizedPath) : null;
        Optional<ManagedMemoryVersion> latestVersion = managedMemoryVersionRepository
                .findTopByMemoryPathOrderByVersionNumberDesc(memoryPath);
        Integer fromVersion = latestVersion.map(ManagedMemoryVersion::getVersionNumber).orElse(null);
        String fromContentHash = latestVersion.map(ManagedMemoryVersion::getContentHash).orElse(hash(previousContent));

        Files.writeString(normalizedPath, markdown, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Date now = new Date();
        int nextVersion = latestVersion.map(ManagedMemoryVersion::getVersionNumber).orElse(0) + 1;
        String toContentHash = hash(markdown);
        Map<String, Object> metadata = buildVersionMetadata(candidate, trigger, summary);
        ManagedMemoryVersion version = new ManagedMemoryVersion(
                null,
                memoryPath,
                nextVersion,
                safe(trigger),
                operation == null ? "" : operation.name(),
                safe(status),
                candidate == null ? "" : safe(candidate.getId()),
                candidate == null ? "" : safe(candidate.getMemoryId()),
                candidate == null ? "" : safe(candidate.getSessionId()),
                candidate == null ? "" : safe(candidate.getDedupeKey()),
                toContentHash,
                now,
                markdown,
                metadata
        );
        managedMemoryVersionRepository.save(version);

        managedMemoryMutationRepository.save(new ManagedMemoryMutation(
                null,
                memoryPath,
                "WRITE",
                safe(trigger),
                operation == null ? "" : operation.name(),
                safe(status),
                candidate == null ? "" : safe(candidate.getId()),
                candidate == null ? "" : safe(candidate.getMemoryId()),
                candidate == null ? "" : safe(candidate.getSessionId()),
                candidate == null ? "" : safe(candidate.getDedupeKey()),
                fromVersion,
                nextVersion,
                fromContentHash,
                toContentHash,
                safe(summary),
                now,
                metadata
        ));
        return new WriteResult(memoryPath, fromVersion, nextVersion, fromContentHash, toContentHash);
    }

    public RollbackResult rollbackManagedFile(String relativePath, int targetVersion, String trigger, String reason) throws IOException {
        String memoryPath = normalizeMemoryPath(relativePath);
        Path basePath = Paths.get(memoryFilesPath).toAbsolutePath().normalize();
        SandboxProtectedActionEnvelope<RollbackResult> envelope = sandboxExecutionService.executeProtectedAction(
                "managedMemoryRollback",
                SandboxTargetType.FILE,
                memoryPath,
                Map.of(
                        "memoryPath", memoryPath,
                        "targetVersion", targetVersion,
                        "trigger", safe(trigger),
                        "reason", safe(reason)
                ),
                basePath.toString(),
                () -> persistRollbackManagedFile(memoryPath, targetVersion, trigger, reason)
        );
        if (!envelope.success() || envelope.result() == null) {
            throw new IOException("managed memory rollback blocked by sandbox: " + safe(envelope.errorMessage()));
        }
        return envelope.result();
    }

    private RollbackResult persistRollbackManagedFile(String memoryPath, int targetVersion, String trigger, String reason) throws IOException {
        ManagedMemoryVersion target = managedMemoryVersionRepository.findByMemoryPathAndVersionNumber(memoryPath, targetVersion)
                .orElseThrow(() -> new IOException("target version not found: " + targetVersion));
        Optional<ManagedMemoryVersion> latestOptional = managedMemoryVersionRepository.findTopByMemoryPathOrderByVersionNumberDesc(memoryPath);
        ManagedMemoryVersion latest = latestOptional.orElseThrow(() -> new IOException("latest version not found"));
        Path absolutePath = resolveAbsolutePath(memoryPath);
        Files.createDirectories(absolutePath.getParent());
        Files.writeString(absolutePath, safe(target.getSnapshotContent()), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Date now = new Date();
        int nextVersion = latest.getVersionNumber() + 1;
        String contentHash = hash(target.getSnapshotContent());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", safe(reason));
        metadata.put("rollbackTargetVersion", targetVersion);
        metadata.put("restoredFromVersion", target.getVersionNumber());
        metadata.put("restoredOperation", safe(target.getOperation()));

        ManagedMemoryVersion restoredVersion = new ManagedMemoryVersion(
                null,
                memoryPath,
                nextVersion,
                safe(trigger),
                "ROLLBACK",
                safe(target.getStatus()),
                safe(target.getSourceCandidateId()),
                safe(target.getMemoryId()),
                safe(target.getSessionId()),
                safe(target.getDedupeKey()),
                contentHash,
                now,
                safe(target.getSnapshotContent()),
                metadata
        );
        managedMemoryVersionRepository.save(restoredVersion);

        managedMemoryMutationRepository.save(new ManagedMemoryMutation(
                null,
                memoryPath,
                "ROLLBACK",
                safe(trigger),
                "ROLLBACK",
                safe(target.getStatus()),
                safe(target.getSourceCandidateId()),
                safe(target.getMemoryId()),
                safe(target.getSessionId()),
                safe(target.getDedupeKey()),
                latest.getVersionNumber(),
                nextVersion,
                safe(latest.getContentHash()),
                contentHash,
                ("rollback to version %d".formatted(targetVersion) + (reason == null || reason.isBlank() ? "" : " - " + reason)),
                now,
                metadata
        ));
        memoryFileRagConfig.loadMemoryFilesIncremental();
        return new RollbackResult(memoryPath, targetVersion, nextVersion, safe(reason), now.toInstant().toString());
    }

    private Map<String, Object> buildWriteArguments(String memoryPath,
                                                    MemoryCandidate candidate,
                                                    MemoryCandidate.CandidateOperation operation,
                                                    String status,
                                                    String trigger,
                                                    String summary) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("memoryPath", safe(memoryPath));
        arguments.put("trigger", safe(trigger));
        arguments.put("status", safe(status));
        arguments.put("summary", safe(summary));
        arguments.put("operation", operation == null ? "" : operation.name());
        if (candidate != null) {
            arguments.put("candidateId", safe(candidate.getId()));
            arguments.put("memoryId", safe(candidate.getMemoryId()));
            arguments.put("sessionId", safe(candidate.getSessionId()));
            arguments.put("dedupeKey", safe(candidate.getDedupeKey()));
        }
        return arguments;
    }

    public List<ManagedMemoryVersion> listVersions(String relativePath, int limit) {
        String memoryPath = normalizeMemoryPath(relativePath);
        List<ManagedMemoryVersion> versions = managedMemoryVersionRepository.findTop100ByMemoryPathOrderByVersionNumberDesc(memoryPath);
        if (limit <= 0 || versions.size() <= limit) {
            return versions;
        }
        return versions.subList(0, limit);
    }

    public List<ManagedMemoryMutation> listRecentMutations(int limit) {
        List<ManagedMemoryMutation> mutations = managedMemoryMutationRepository.findTop100ByOrderByCreatedAtDesc();
        if (limit <= 0 || mutations.size() <= limit) {
            return mutations;
        }
        return mutations.subList(0, limit);
    }

    public long countVersions(String relativePath) {
        return managedMemoryVersionRepository.countByMemoryPath(normalizeMemoryPath(relativePath));
    }

    public long countMutations(String relativePath) {
        return managedMemoryMutationRepository.countByMemoryPath(normalizeMemoryPath(relativePath));
    }

    public Integer findLatestVersionNumber(String relativePath) {
        return managedMemoryVersionRepository.findTopByMemoryPathOrderByVersionNumberDesc(normalizeMemoryPath(relativePath))
                .map(ManagedMemoryVersion::getVersionNumber)
                .orElse(0);
    }

    public VersionDiffResult diff(String relativePath, int fromVersion, int toVersion) throws IOException {
        String memoryPath = normalizeMemoryPath(relativePath);
        ManagedMemoryVersion from = managedMemoryVersionRepository.findByMemoryPathAndVersionNumber(memoryPath, fromVersion)
                .orElseThrow(() -> new IOException("from version not found: " + fromVersion));
        ManagedMemoryVersion to = managedMemoryVersionRepository.findByMemoryPathAndVersionNumber(memoryPath, toVersion)
                .orElseThrow(() -> new IOException("to version not found: " + toVersion));
        DiffMetrics metrics = calculateDiffMetrics(from.getSnapshotContent(), to.getSnapshotContent());
        return new VersionDiffResult(
                memoryPath,
                toSnapshot(from),
                toSnapshot(to),
                metrics.changedLines(),
                metrics.commonPrefixLines(),
                metrics.commonSuffixLines(),
                buildPreview(from.getSnapshotContent(), to.getSnapshotContent(), metrics),
                "from version %d to version %d".formatted(fromVersion, toVersion)
        );
    }

    public Path resolveAbsolutePath(String relativePath) throws IOException {
        Path basePath = Paths.get(memoryFilesPath).toAbsolutePath().normalize();
        Path filePath = basePath.resolve(relativePath).normalize();
        if (!filePath.startsWith(basePath)) {
            throw new IOException("illegal memory file path");
        }
        return filePath;
    }

    private VersionSnapshot toSnapshot(ManagedMemoryVersion version) {
        return new VersionSnapshot(
                version.getMemoryPath(),
                version.getVersionNumber(),
                safe(version.getTrigger()),
                safe(version.getOperation()),
                safe(version.getStatus()),
                safe(version.getSourceCandidateId()),
                safe(version.getMemoryId()),
                safe(version.getSessionId()),
                safe(version.getDedupeKey()),
                safe(version.getContentHash()),
                version.getCreatedAt() == null ? null : version.getCreatedAt().toInstant().toString(),
                safe(version.getSnapshotContent()),
                version.getMetadata() == null ? Map.of() : version.getMetadata()
        );
    }

    private DiffMetrics calculateDiffMetrics(String before, String after) {
        String[] oldLines = splitLines(before);
        String[] newLines = splitLines(after);
        int prefix = 0;
        while (prefix < oldLines.length && prefix < newLines.length && oldLines[prefix].equals(newLines[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix + prefix < oldLines.length
                && suffix + prefix < newLines.length
                && oldLines[oldLines.length - 1 - suffix].equals(newLines[newLines.length - 1 - suffix])) {
            suffix++;
        }
        int changedOld = Math.max(0, oldLines.length - prefix - suffix);
        int changedNew = Math.max(0, newLines.length - prefix - suffix);
        return new DiffMetrics(prefix, suffix, Math.max(changedOld, changedNew));
    }

    private List<String> buildPreview(String before, String after, DiffMetrics metrics) {
        String[] oldLines = splitLines(before);
        String[] newLines = splitLines(after);
        int oldStart = metrics.commonPrefixLines();
        int oldEnd = Math.max(oldStart, oldLines.length - metrics.commonSuffixLines());
        int newStart = metrics.commonPrefixLines();
        int newEnd = Math.max(newStart, newLines.length - metrics.commonSuffixLines());
        List<String> preview = new ArrayList<>();
        for (int i = oldStart; i < oldEnd; i++) {
            preview.add("- " + oldLines[i]);
        }
        for (int i = newStart; i < newEnd; i++) {
            preview.add("+ " + newLines[i]);
        }
        if (preview.isEmpty()) {
            preview.add("no line changes");
        }
        return preview;
    }

    private String[] splitLines(String value) {
        return safe(value).split("\\R", -1);
    }

    private String normalizeMemoryPath(String relativePath) {
        return relativePath == null ? "" : relativePath.replace('\\', '/').trim();
    }

    private Map<String, Object> buildVersionMetadata(MemoryCandidate candidate, String trigger, String summary) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("trigger", safe(trigger));
        metadata.put("summary", safe(summary));
        if (candidate == null) {
            return metadata;
        }
        metadata.put("candidateId", safe(candidate.getId()));
        metadata.put("sourceSummaryVersion", candidate.getSourceSummaryVersion());
        metadata.put("topic", safe(candidate.getTopic()));
        metadata.put("summaryType", safe(candidate.getSummaryType()));
        if (candidate.getMetadata() != null) {
            metadata.putAll(candidate.getMetadata());
        }
        return metadata;
    }

    private String hash(String content) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            byte[] digest = messageDigest.digest(safe(content).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(safe(content).hashCode());
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record WriteResult(String memoryPath,
                              Integer fromVersion,
                              Integer toVersion,
                              String fromContentHash,
                              String toContentHash) {
    }

    public record RollbackResult(String memoryPath,
                                 int targetVersion,
                                 int restoredVersion,
                                 String reason,
                                 String executedAt) {
    }

    public record VersionSnapshot(String memoryPath,
                                  Integer versionNumber,
                                  String trigger,
                                  String operation,
                                  String status,
                                  String sourceCandidateId,
                                  String memoryId,
                                  String sessionId,
                                  String dedupeKey,
                                  String contentHash,
                                  String createdAt,
                                  String content,
                                  Map<String, Object> metadata) {
    }

    public record VersionDiffResult(String memoryPath,
                                    VersionSnapshot fromVersion,
                                    VersionSnapshot toVersion,
                                    int changedLines,
                                    int commonPrefixLines,
                                    int commonSuffixLines,
                                    List<String> previewLines,
                                    String summary) {
    }

    private record DiffMetrics(int commonPrefixLines, int commonSuffixLines, int changedLines) {
    }
}
