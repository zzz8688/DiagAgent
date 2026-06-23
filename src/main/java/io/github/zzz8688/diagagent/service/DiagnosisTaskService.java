package io.github.zzz8688.diagagent.service;

import io.github.zzz8688.diagagent.dto.DiagnosisConclusion;
import io.github.zzz8688.diagagent.entity.DiagnosisTask;
import io.github.zzz8688.diagagent.entity.DiagnosisTaskStatus;
import io.github.zzz8688.diagagent.repository.DiagnosisTaskRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DiagnosisTaskService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisTaskService.class);

    private final DiagnosisTaskRepository repository;
    private final RedisIdentityGuardService redisIdentityGuardService;

    public DiagnosisTask createTask(String userQuery, String sessionId) {
        String taskId = UUID.randomUUID().toString();
        DiagnosisTask task = new DiagnosisTask();
        task.setId(taskId);
        task.setUserQuery(userQuery);
        task.setSessionId(sessionId);
        task.setStatus(DiagnosisTaskStatus.SUBMITTED);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        DiagnosisTask saved = repository.save(task);
        redisIdentityGuardService.markTaskId(taskId);
        return saved;
    }

    public Optional<DiagnosisTask> getTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        if (redisIdentityGuardService.isTaskNullCached(taskId)) {
            return Optional.empty();
        }
        if (!redisIdentityGuardService.mightContainTaskId(taskId)) {
            redisIdentityGuardService.cacheMissingTask(taskId);
            return Optional.empty();
        }
        Optional<DiagnosisTask> task = repository.findById(taskId);
        if (task.isPresent()) {
            redisIdentityGuardService.markTaskId(taskId);
            return task;
        }
        redisIdentityGuardService.cacheMissingTask(taskId);
        return Optional.empty();
    }

    public void updateStatus(String taskId, DiagnosisTaskStatus status) {
        repository.findById(taskId).ifPresent(task -> {
            task.setStatus(status);
            task.setUpdatedAt(LocalDateTime.now());
            repository.save(task);
        });
    }

    public void completeTask(String taskId, DiagnosisConclusion result) {
        repository.findById(taskId).ifPresent(task -> {
            task.setStatus(DiagnosisTaskStatus.COMPLETED);
            task.setResult(result);
            task.setUpdatedAt(LocalDateTime.now());
            repository.save(task);
            log.info("Task completed: {}", taskId);
        });
    }

    public void failTask(String taskId, String errorMessage) {
        repository.findById(taskId).ifPresent(task -> {
            task.setStatus(DiagnosisTaskStatus.FAILED);
            task.setErrorMessage(errorMessage);
            task.setUpdatedAt(LocalDateTime.now());
            repository.save(task);
            log.error("Task failed: {}, error: {}", taskId, errorMessage);
        });
    }
}
