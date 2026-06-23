package io.github.zzz8688.diagagent.repository;

import io.github.zzz8688.diagagent.entity.DiagnosisTask;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DiagnosisTaskRepository extends MongoRepository<DiagnosisTask, String> {
}
