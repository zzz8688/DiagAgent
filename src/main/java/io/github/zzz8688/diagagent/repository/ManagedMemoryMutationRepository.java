package io.github.zzz8688.diagagent.repository;

import io.github.zzz8688.diagagent.entity.ManagedMemoryMutation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ManagedMemoryMutationRepository extends MongoRepository<ManagedMemoryMutation, String> {

    List<ManagedMemoryMutation> findTop100ByOrderByCreatedAtDesc();

    List<ManagedMemoryMutation> findTop100ByMemoryPathOrderByCreatedAtDesc(String memoryPath);

    long countByMemoryPath(String memoryPath);
}
