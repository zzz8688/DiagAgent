package io.github.zzz8688.diagagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReactLoopGuardService {

    private static final Logger log = LoggerFactory.getLogger(ReactLoopGuardService.class);

    private final StopGenerateService stopGenerateService;
    private final int maxToolCallsPerQuestion;
    private final int maxConsecutiveSameCall;
    private final int maxCallsPerToolPerQuestion;
    private final Map<String, LoopState> states = new ConcurrentHashMap<>();

    public ReactLoopGuardService(StopGenerateService stopGenerateService,
                                 @Value("${chat.react-loop.max-tool-calls-per-question:24}") int maxToolCallsPerQuestion,
                                 @Value("${chat.react-loop.max-consecutive-same-call:5}") int maxConsecutiveSameCall,
                                 @Value("${chat.react-loop.max-calls-per-tool-per-question:2}") int maxCallsPerToolPerQuestion) {
        this.stopGenerateService = stopGenerateService;
        this.maxToolCallsPerQuestion = maxToolCallsPerQuestion;
        this.maxConsecutiveSameCall = maxConsecutiveSameCall;
        this.maxCallsPerToolPerQuestion = maxCallsPerToolPerQuestion;
    }

    public void startQuestion(String sessionId, String query) {
        states.put(sessionId, new LoopState(query));
    }

    public void finishQuestion(String sessionId) {
        states.remove(sessionId);
    }

    public void onToolCall(String sessionId, String toolName, String toolInput) {
        LoopState state = states.computeIfAbsent(sessionId, key -> new LoopState(""));
        LoopSnapshot snapshot = state.record(toolName, toolInput);
        if (snapshot.totalCalls() > maxToolCallsPerQuestion) {
            stopGenerateService.cancelTask(sessionId);
            throw new BaseToolLoopException("检测到工具调用总次数超过阈值，已中断本轮诊断");
        }
        if (snapshot.consecutiveSameCalls() > maxConsecutiveSameCall) {
            stopGenerateService.cancelTask(sessionId);
            throw new BaseToolLoopException("检测到重复工具调用循环，已中断本轮诊断");
        }
        if (snapshot.callsForTool() > maxCallsPerToolPerQuestion) {
            stopGenerateService.cancelTask(sessionId);
            throw new BaseToolLoopException("检测到同一工具被重复调用过多次，已中断本轮诊断");
        }
    }

    private static final class LoopState {
        private final String query;
        private int totalCalls;
        private int consecutiveSameCalls;
        private String lastSignature;
        private final Map<String, Integer> callsByTool = new ConcurrentHashMap<>();

        private LoopState(String query) {
            this.query = query;
        }

        private synchronized LoopSnapshot record(String toolName, String toolInput) {
            totalCalls++;
            String normalizedToolName = normalizeToolName(toolName);
            String normalizedInput = toolInput == null ? "" : toolInput.replaceAll("\\s+", " ").trim();
            if (normalizedInput.length() > 200) {
                normalizedInput = normalizedInput.substring(0, 200);
            }
            String signature = normalizedToolName + "::" + normalizedInput;
            if (signature.equals(lastSignature)) {
                consecutiveSameCalls++;
            } else {
                lastSignature = signature;
                consecutiveSameCalls = 1;
            }
            int callsForTool = callsByTool.merge(normalizedToolName, 1, Integer::sum);
            log.warn("ReAct 调用跟踪: query={}, tool={}, totalCalls={}, consecutiveSameCalls={}",
                    query, normalizedToolName, totalCalls, consecutiveSameCalls);
            return new LoopSnapshot(totalCalls, consecutiveSameCalls, callsForTool);
        }

        private String normalizeToolName(String toolName) {
            return toolName == null ? "" : toolName.replaceAll("\\s+", "").trim().toLowerCase();
        }
    }

    private record LoopSnapshot(int totalCalls, int consecutiveSameCalls, int callsForTool) {
    }

    public static class BaseToolLoopException extends RuntimeException {
        public BaseToolLoopException(String message) {
            super(message);
        }
    }
}
