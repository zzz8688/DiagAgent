package io.github.zzz8688.diagagent.repository;

import io.github.zzz8688.diagagent.entity.ProcessedMemoryFile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ProcessedMemoryFileRepository extends MongoRepository<ProcessedMemoryFile, String> {

    Optional<ProcessedMemoryFile> findByMemoryPath(String memoryPath);
}
