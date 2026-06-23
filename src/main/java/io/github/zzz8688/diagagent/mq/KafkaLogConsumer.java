package io.github.zzz8688.diagagent.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zzz8688.diagagent.agent.runtime.LogObservationNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaLogConsumer {

    private final ObjectMapper objectMapper;
    private final LogObservationNormalizer logObservationNormalizer;
    private final LongAdder consumedCount = new LongAdder();
    private final LongAdder parseFailedCount = new LongAdder();

    @Value("${diagagent.kafka.log-progress-log-interval:100}")
    private long progressLogInterval;

    @KafkaListener(
            topics = "${diagagent.kafka.log-ingest-topic:log-ingest}",
            groupId = "${diagagent.kafka.log-ingest-group:diag-agent-log-consumer}"
    )
    public void consume(String rawMessage, Acknowledgment acknowledgment) {
        if (rawMessage == null || rawMessage.isBlank()) {
            acknowledge(acknowledgment);
            return;
        }
        try {
            KafkaLogMessage message = objectMapper.readValue(rawMessage, KafkaLogMessage.class);
            consumedCount.increment();
            LogObservationNormalizer.LogIngestOutcome outcome = logObservationNormalizer.accept(message);
            maybeLogProgress(outcome);
        } catch (Exception ex) {
            parseFailedCount.increment();
            log.warn("Kafka 日志消息解析失败: {}", rawMessage, ex);
        } finally {
            acknowledge(acknowledgment);
        }
    }

    private void acknowledge(Acknowledgment acknowledgment) {
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }

    private void maybeLogProgress(LogObservationNormalizer.LogIngestOutcome outcome) {
        if (outcome == null) {
            return;
        }
        long consumed = consumedCount.sum();
        long indexed = outcome.indexedCount();
        long interval = progressLogInterval <= 0 ? 100 : progressLogInterval;
        boolean shouldLog = indexed > 0 && indexed <= 3;
        shouldLog = shouldLog || (consumed % interval == 0);
        if (!shouldLog) {
            return;
        }
        log.info("Kafka 日志消费进度: consumed={}, parseFailed={}, filtered={}, indexed={}, lastResult={}, lastMessageId={}, level={}, service={}",
                consumed,
                parseFailedCount.sum(),
                outcome.filteredCount(),
                indexed,
                outcome.indexed() ? "INDEXED" : outcome.filtered() ? "FILTERED" : "IGNORED",
                outcome.messageId(),
                outcome.level(),
                outcome.service());
    }
}
