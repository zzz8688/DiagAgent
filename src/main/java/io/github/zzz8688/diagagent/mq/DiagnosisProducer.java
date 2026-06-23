package io.github.zzz8688.diagagent.mq;

import io.github.zzz8688.diagagent.agent.runtime.MultiAgentReActRuntime;
import io.github.zzz8688.diagagent.dto.DiagnosisConclusion;
import io.github.zzz8688.diagagent.dto.DiagnosisMessage;
import io.github.zzz8688.diagagent.entity.DiagnosisTaskStatus;
import io.github.zzz8688.diagagent.service.DiagnosisTaskService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DiagnosisProducer {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisProducer.class);

    private final DiagnosisTaskService diagnosisTaskService;
    private final MultiAgentReActRuntime multiAgentReActRuntime;

    public void sendDiagnosisMessage(DiagnosisMessage message) {
        if (message == null || message.getTaskId() == null) {
            return;
        }
        try {
            diagnosisTaskService.updateStatus(message.getTaskId(), DiagnosisTaskStatus.RUNNING);
            DiagnosisConclusion result = multiAgentReActRuntime.executeDiagnosis(message.getUserQuery(), message.getSessionId(), message.isReadOnly());
            diagnosisTaskService.completeTask(message.getTaskId(), result);
        } catch (Exception e) {
            log.error("异步诊断执行失败: taskId={}", message.getTaskId(), e);
            diagnosisTaskService.failTask(message.getTaskId(), e.getMessage());
        }
    }
}
