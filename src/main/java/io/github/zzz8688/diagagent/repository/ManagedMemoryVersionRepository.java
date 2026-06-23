package io.github.zzz8688.diagagent.repository;

import io.github.zzz8688.diagagent.entity.ManagedMemoryVersion;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ManagedMemoryVersionRepository extends MongoRepository<ManagedMemoryVersion, String> {

    Optional<ManagedMemoryVersion> findTopByMemoryPathOrderByVersionNumberDesc(String memoryPath);

    Optional<ManagedMemoryVersion> findByMemoryPathAndVersionNumber(String memoryPath, Integer versionNumber);

    List<ManagedMemoryVersion> findTop100ByMemoryPathOrderByVersionNumberDesc(String memoryPath);

    long countByMemoryPath(String memoryPath);
}
