package io.github.zzz8688.diagagent.repository;

import io.github.zzz8688.diagagent.entity.MemoryCandidate;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface MemoryCandidateRepository extends MongoRepository<MemoryCandidate, String> {

    Optional<MemoryCandidate> findByMemoryIdAndSourceSummaryVersion(String memoryId, Integer sourceSummaryVersion);

    List<MemoryCandidate> findTop50ByStatusOrderByUpdatedAtAsc(MemoryCandidate.CandidateStatus status);

    List<MemoryCandidate> findTop50ByOrderByUpdatedAtDesc();

    long countByStatus(MemoryCandidate.CandidateStatus status);
}
