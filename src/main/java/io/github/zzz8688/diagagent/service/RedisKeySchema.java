package io.github.zzz8688.diagagent.service;

import java.time.Duration;

public final class RedisKeySchema {

    public static final Duration ANSWER_CACHE_TTL = Duration.ofMinutes(10);
    public static final Duration ANSWER_CACHE_PHYSICAL_TTL = Duration.ofMinutes(30);
    public static final Duration ANSWER_NULL_TTL = Duration.ofMinutes(2);
    public static final Duration ANSWER_NULL_PHYSICAL_TTL = Duration.ofMinutes(5);
    public static final Duration GENERATING_TTL = Duration.ofMinutes(10);
    public static final Duration SESSION_STATE_TTL = Duration.ofMinutes(15);
    public static final Duration STOP_SIGNAL_TTL = Duration.ofSeconds(30);
    public static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(15);
    public static final Duration SESSION_EXECUTION_LOCK_TTL = Duration.ofMinutes(5);
    public static final Duration TASK_LIFECYCLE_LOCK_TTL = Duration.ofSeconds(30);
    public static final Duration NULL_CACHE_TTL = Duration.ofMinutes(3);
    public static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);
    public static final Duration LOCAL_ANSWER_CACHE_TTL = Duration.ofSeconds(30);
    public static final int LOCAL_ANSWER_CACHE_MAX_SIZE = 2048;
    public static final long SESSION_BLOOM_BITS = 1_048_576L;
    public static final long TASK_BLOOM_BITS = 1_048_576L;
    public static final int BLOOM_HASH_COUNT = 3;

    private static final String ROOT = "diag";

    private RedisKeySchema() {
    }

    public static String answerCache(String knowledgeVersion, String engine, String questionHash) {
        return ROOT + ":answer:cache:" + safe(knowledgeVersion) + ":" + safe(engine) + ":" + safe(questionHash);
    }

    public static String generatingState(String sessionId) {
        return ROOT + ":session:generating:" + safe(sessionId);
    }

    public static String sessionState(String sessionId) {
        return ROOT + ":session:state:" + safe(sessionId);
    }

    public static String knowledgeVersion() {
        return ROOT + ":knowledge:version";
    }

    public static String stopSignal(String sessionId, boolean interrupt) {
        return ROOT + ":idempotent:stop:" + safe(sessionId) + ":" + interrupt;
    }

    public static String answerRebuildLock(String knowledgeVersion, String engine, String questionHash) {
        return ROOT + ":lock:answer-rebuild:" + safe(knowledgeVersion) + ":" + safe(engine) + ":" + safe(questionHash);
    }

    public static String answerHotKeyZset() {
        return ROOT + ":hotkeys:answer";
    }

    public static String sessionExecutionLock(String sessionId) {
        return ROOT + ":lock:session:" + safe(sessionId);
    }

    public static String taskLifecycleLock(String taskId) {
        return ROOT + ":lock:task:" + safe(taskId);
    }

    public static String nullSessionCache(String sessionId) {
        return ROOT + ":null:session:" + safe(sessionId);
    }

    public static String nullTaskCache(String taskId) {
        return ROOT + ":null:task:" + safe(taskId);
    }

    public static String bloomSession() {
        return ROOT + ":bloom:session";
    }

    public static String bloomTask() {
        return ROOT + ":bloom:task";
    }

    public static String rateLimit(String scope, String identity, String bucket) {
        return ROOT + ":rate:" + safe(scope) + ":" + safe(identity) + ":" + safe(bucket);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
