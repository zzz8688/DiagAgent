package io.github.zzz8688.diagagent.repository;

import io.github.zzz8688.diagagent.entity.ProcessedDocumentFile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ProcessedDocumentFileRepository extends MongoRepository<ProcessedDocumentFile, String> {

    Optional<ProcessedDocumentFile> findByFilename(String filename);
}
