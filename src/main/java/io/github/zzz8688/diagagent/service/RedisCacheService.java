package io.github.zzz8688.diagagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheService {

    private static final String DEFAULT_KNOWLEDGE_VERSION = "init";
    private static final String SESSION_STATUS_RUNNING = "RUNNING";
    private static final String SESSION_STATUS_STOPPED = "STOPPED";
    private static final ExecutorService ANSWER_REBUILD_EXECUTOR = Executors.newFixedThreadPool(4);

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisDistributedLockService redisDistributedLockService;
    private final ObjectMapper objectMapper;

    // L1 本地热点缓存，对齐 item-service 的多级缓存思路。
    private final Cache<String, CacheEntry> localAnswerCache = Caffeine.newBuilder()
            .maximumSize(RedisKeySchema.LOCAL_ANSWER_CACHE_MAX_SIZE)
            .expireAfterWrite(RedisKeySchema.LOCAL_ANSWER_CACHE_TTL)
            .build();

    // Redis 不可用时的最小降级，保证停止生成和缓存功能不会整条链路报错。
    private final Map<String, CacheEntry> fallbackAnswerCache = new ConcurrentHashMap<>();
    private final Map<String, Long> fallbackGeneratingSessions = new ConcurrentHashMap<>();
    private volatile String fallbackKnowledgeVersion = DEFAULT_KNOWLEDGE_VERSION;

    public String hashQuestion(String question) {
        String value = question == null ? "" : question.trim();
        return DigestUtils.md5DigestAsHex(value.getBytes(StandardCharsets.UTF_8));
    }

    public AnswerCacheLookup lookupAnswerCache(String questionHash, String engine) {
        String knowledgeVersion = getKnowledgeVersion();
        String key = RedisKeySchema.answerCache(knowledgeVersion, engine, questionHash);
        long now = System.currentTimeMillis();
        CacheEntry localEntry = localAnswerCache.getIfPresent(key);
        if (localEntry != null && !localEntry.localExpired(now)) {
            trackHotAnswerKey(key);
            return toLookup(key, localEntry, now);
        }
        try {
            String payload = stringRedisTemplate.opsForValue().get(key);
            if (payload == null) {
                return AnswerCacheLookup.miss(key);
            }
            AnswerCachePayload cachePayload = deserializePayload(payload, now);
            CacheEntry cacheEntry = new CacheEntry(
                    cachePayload.answer(),
                    now + RedisKeySchema.LOCAL_ANSWER_CACHE_TTL.toMillis(),
                    cachePayload.logicalExpireAt(),
                    cachePayload.empty()
            );
            localAnswerCache.put(key, cacheEntry);
            trackHotAnswerKey(key);
            return toLookup(key, cacheEntry, now);
        } catch (Exception ex) {
            log.warn("Redis 读取诊断缓存失败，降级到本地缓存: key={}", key, ex);
            CacheEntry entry = fallbackAnswerCache.get(key);
            if (entry == null || entry.localExpired(now)) {
                fallbackAnswerCache.remove(key);
                return AnswerCacheLookup.miss(key);
            }
            return toLookup(key, entry, now);
        }
    }

    public String getCachedAnswer(String questionHash, String engine) {
        return lookupAnswerCache(questionHash, engine).cacheableAnswer();
    }

    public void cacheAnswer(String questionHash, String engine, String answer) {
        String knowledgeVersion = getKnowledgeVersion();
        String key = RedisKeySchema.answerCache(knowledgeVersion, engine, questionHash);
        writeAnswerPayload(key, safe(answer), false, RedisKeySchema.ANSWER_CACHE_TTL, RedisKeySchema.ANSWER_CACHE_PHYSICAL_TTL);
        trackHotAnswerKey(key);
    }

    public void cacheEmptyAnswer(String questionHash, String engine) {
        String knowledgeVersion = getKnowledgeVersion();
        String key = RedisKeySchema.answerCache(knowledgeVersion, engine, questionHash);
        writeAnswerPayload(key, "", true, RedisKeySchema.ANSWER_NULL_TTL, RedisKeySchema.ANSWER_NULL_PHYSICAL_TTL);
    }

    public void rebuildAnswerCacheAsync(String questionHash,
                                        String engine,
                                        String trigger,
                                        Supplier<String> rebuildAction) {
        String lockToken = tryAcquireAnswerRebuildLock(questionHash, engine);
        if (lockToken == null) {
            return;
        }
        ANSWER_REBUILD_EXECUTOR.submit(() -> {
            try {
                String rebuilt = rebuildAction.get();
                if (rebuilt == null || rebuilt.isBlank()) {
                    cacheEmptyAnswer(questionHash, engine);
                    return;
                }
                cacheAnswer(questionHash, engine, rebuilt);
                log.info("异步重建热点答案缓存完成: engine={}, questionHash={}, trigger={}", engine, questionHash, trigger);
            } catch (Exception ex) {
                log.warn("异步重建热点答案缓存失败: engine={}, questionHash={}, trigger={}", engine, questionHash, trigger, ex);
            } finally {
                releaseAnswerRebuildLock(questionHash, engine, lockToken);
            }
        });
    }

    public String tryAcquireAnswerRebuildLock(String questionHash, String engine) {
        String lockKey = RedisKeySchema.answerRebuildLock(getKnowledgeVersion(), engine, questionHash);
        try {
            return redisDistributedLockService.tryLock(lockKey, RedisKeySchema.REBUILD_LOCK_TTL);
        } catch (Exception ex) {
            log.warn("Redis 获取答案重建锁失败: key={}", lockKey, ex);
            return null;
        }
    }

    public boolean releaseAnswerRebuildLock(String questionHash, String engine, String token) {
        String lockKey = RedisKeySchema.answerRebuildLock(getKnowledgeVersion(), engine, questionHash);
        try {
            return redisDistributedLockService.unlock(lockKey, token);
        } catch (Exception ex) {
            log.warn("Redis 释放答案重建锁失败: key={}", lockKey, ex);
            return false;
        }
    }

    public ClearAnswerCacheResult clearAnswerCache() {
        localAnswerCache.invalidateAll();
        fallbackAnswerCache.clear();
        long deletedAnswerKeys = 0L;
        long deletedLockKeys = 0L;
        boolean deletedHotKeyIndex = false;
        try {
            Set<String> keys = stringRedisTemplate.keys("diag:answer:cache:*");
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
                deletedAnswerKeys = keys.size();
            }
            Set<String> lockKeys = stringRedisTemplate.keys("diag:lock:answer-rebuild:*");
            if (lockKeys != null && !lockKeys.isEmpty()) {
                stringRedisTemplate.delete(lockKeys);
                deletedLockKeys = lockKeys.size();
            }
            deletedHotKeyIndex = Boolean.TRUE.equals(stringRedisTemplate.delete(RedisKeySchema.answerHotKeyZset()));
        } catch (Exception ex) {
            log.warn("Redis 清理诊断缓存失败，已仅清理本地缓存", ex);
            return new ClearAnswerCacheResult(deletedAnswerKeys, deletedLockKeys, deletedHotKeyIndex, true);
        }
        return new ClearAnswerCacheResult(deletedAnswerKeys, deletedLockKeys, deletedHotKeyIndex, false);
    }

    public void updateKnowledgeVersion(String version) {
        String normalized = safe(version);
        if (normalized.isBlank()) {
            return;
        }
        fallbackKnowledgeVersion = normalized;
        try {
            stringRedisTemplate.opsForValue().set(RedisKeySchema.knowledgeVersion(), normalized);
        } catch (Exception ex) {
            log.warn("Redis 更新 knowledgeVersion 失败，保留本地版本: version={}", normalized, ex);
        }
    }

    public void startGenerate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        clearStopSignals(sessionId);
        long expireAt = System.currentTimeMillis() + RedisKeySchema.GENERATING_TTL.toMillis();
        try {
            String generatingKey = RedisKeySchema.generatingState(sessionId);
            stringRedisTemplate.opsForValue().set(generatingKey, "1", RedisKeySchema.GENERATING_TTL);
            Map<String, String> state = new LinkedHashMap<>();
            state.put("status", SESSION_STATUS_RUNNING);
            state.put("updatedAt", Instant.now().toString());
            state.put("expireAt", String.valueOf(expireAt));
            stringRedisTemplate.opsForHash().putAll(RedisKeySchema.sessionState(sessionId), state);
            stringRedisTemplate.expire(RedisKeySchema.sessionState(sessionId), RedisKeySchema.SESSION_STATE_TTL);
        } catch (Exception ex) {
            log.warn("Redis 标记生成开始失败，降级到本地状态: sessionId={}", sessionId, ex);
            fallbackGeneratingSessions.put(sessionId, expireAt);
        }
    }

    public void stopGenerate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        fallbackGeneratingSessions.remove(sessionId);
        try {
            stringRedisTemplate.delete(RedisKeySchema.generatingState(sessionId));
            stringRedisTemplate.opsForHash().putAll(
                    RedisKeySchema.sessionState(sessionId),
                    Map.of(
                            "status", SESSION_STATUS_STOPPED,
                            "updatedAt", Instant.now().toString()
                    )
            );
            stringRedisTemplate.expire(RedisKeySchema.sessionState(sessionId), Duration.ofMinutes(1));
        } catch (Exception ex) {
            log.warn("Redis 清理生成状态失败: sessionId={}", sessionId, ex);
        }
    }

    public boolean tryMarkStopSignal(String sessionId, boolean shouldInterrupt) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(
                    RedisKeySchema.stopSignal(sessionId, shouldInterrupt),
                    Instant.now().toString(),
                    RedisKeySchema.STOP_SIGNAL_TTL
            );
            return Boolean.TRUE.equals(success);
        } catch (Exception ex) {
            log.warn("Redis 写入 stop 幂等 key 失败，降级为允许继续处理: sessionId={}, interrupt={}", sessionId, shouldInterrupt, ex);
            return true;
        }
    }

    public void clearStopSignals(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.delete(List.of(
                    RedisKeySchema.stopSignal(sessionId, false),
                    RedisKeySchema.stopSignal(sessionId, true)
            ));
        } catch (Exception ex) {
            log.debug("清理 stop 幂等 key 失败: sessionId={}", sessionId, ex);
        }
    }

    public boolean isGenerating(String sessionId) {
        return shouldContinue(sessionId);
    }

    public boolean shouldContinue(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            Long ttl = stringRedisTemplate.getExpire(RedisKeySchema.generatingState(sessionId), TimeUnit.MILLISECONDS);
            if (ttl == null) {
                return fallbackShouldContinue(sessionId);
            }
            if (ttl > 0 || ttl == -1L) {
                return true;
            }
            return false;
        } catch (Exception ex) {
            log.warn("Redis 检查生成状态失败，降级到本地状态: sessionId={}", sessionId, ex);
            return fallbackShouldContinue(sessionId);
        }
    }

    public long getRemainingTime(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0L;
        }
        try {
            Long ttl = stringRedisTemplate.getExpire(RedisKeySchema.generatingState(sessionId), TimeUnit.MILLISECONDS);
            if (ttl == null || ttl < 0) {
                return fallbackRemainingTime(sessionId);
            }
            return ttl;
        } catch (Exception ex) {
            log.warn("Redis 获取剩余时间失败，降级到本地状态: sessionId={}", sessionId, ex);
            return fallbackRemainingTime(sessionId);
        }
    }

    public Map<Object, Object> getSessionState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of();
        }
        try {
            return stringRedisTemplate.opsForHash().entries(RedisKeySchema.sessionState(sessionId));
        } catch (Exception ex) {
            log.warn("Redis 获取会话状态失败: sessionId={}", sessionId, ex);
            return Map.of();
        }
    }

    public Set<String> getTopHotAnswerKeys(long limit) {
        try {
            Set<String> hotKeys = stringRedisTemplate.opsForZSet().reverseRange(RedisKeySchema.answerHotKeyZset(), 0, Math.max(0, limit - 1));
            return hotKeys == null ? Set.of() : hotKeys;
        } catch (Exception ex) {
            log.warn("Redis 读取热点答案 key 失败", ex);
            return Set.of();
        }
    }

    public List<HotAnswerKeyStat> getTopHotAnswerStats(long limit) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeWithScores(RedisKeySchema.answerHotKeyZset(), 0, Math.max(0, limit - 1));
            if (tuples == null || tuples.isEmpty()) {
                return List.of();
            }
            return tuples.stream()
                    .filter(tuple -> tuple.getValue() != null)
                    .map(tuple -> new HotAnswerKeyStat(tuple.getValue(), tuple.getScore() == null ? 0.0d : tuple.getScore()))
                    .toList();
        } catch (Exception ex) {
            log.warn("Redis 读取热点答案统计失败", ex);
            return List.of();
        }
    }

    public WarmupResult warmTopHotAnswerKeys(long limit) {
        List<HotAnswerKeyStat> hotKeys = getTopHotAnswerStats(limit);
        if (hotKeys.isEmpty()) {
            return new WarmupResult(limit, 0L, 0L, 0L);
        }
        long warmed = 0L;
        long missing = 0L;
        long failed = 0L;
        long now = System.currentTimeMillis();
        for (HotAnswerKeyStat stat : hotKeys) {
            if (stat == null || stat.cacheKey() == null || stat.cacheKey().isBlank()) {
                continue;
            }
            try {
                String payload = stringRedisTemplate.opsForValue().get(stat.cacheKey());
                if (payload == null) {
                    missing++;
                    continue;
                }
                AnswerCachePayload cachePayload = deserializePayload(payload, now);
                localAnswerCache.put(stat.cacheKey(), new CacheEntry(
                        cachePayload.answer(),
                        now + RedisKeySchema.LOCAL_ANSWER_CACHE_TTL.toMillis(),
                        cachePayload.logicalExpireAt(),
                        cachePayload.empty()
                ));
                warmed++;
            } catch (Exception ex) {
                failed++;
                log.warn("热点答案预热失败: key={}", stat.cacheKey(), ex);
            }
        }
        return new WarmupResult(limit, warmed, missing, failed);
    }

    public long countGeneratingSessions() {
        return countKeys("diag:session:generating:*");
    }

    public long countCachedAnswerKeys() {
        return countKeys("diag:answer:cache:*");
    }

    public boolean isRedisAvailable() {
        try {
            stringRedisTemplate.hasKey(RedisKeySchema.knowledgeVersion());
            return true;
        } catch (Exception ex) {
            log.warn("Redis 可用性探测失败", ex);
            return false;
        }
    }

    public boolean isFallbackActive() {
        return !fallbackAnswerCache.isEmpty() || !fallbackGeneratingSessions.isEmpty();
    }

    public String getKnowledgeVersion() {
        try {
            String version = stringRedisTemplate.opsForValue().get(RedisKeySchema.knowledgeVersion());
            if (version != null && !version.isBlank()) {
                fallbackKnowledgeVersion = version;
                return version;
            }
            stringRedisTemplate.opsForValue().setIfAbsent(RedisKeySchema.knowledgeVersion(), fallbackKnowledgeVersion);
        } catch (Exception ex) {
            log.warn("Redis 获取 knowledgeVersion 失败，降级到本地版本", ex);
        }
        return fallbackKnowledgeVersion;
    }

    private void writeAnswerPayload(String key, String answer, boolean empty, Duration logicalTtl, Duration physicalTtl) {
        long now = System.currentTimeMillis();
        CacheEntry entry = new CacheEntry(
                answer,
                now + RedisKeySchema.LOCAL_ANSWER_CACHE_TTL.toMillis(),
                now + logicalTtl.toMillis(),
                empty
        );
        localAnswerCache.put(key, entry);
        try {
            String payload = objectMapper.writeValueAsString(new AnswerCachePayload(
                    answer,
                    empty,
                    now,
                    now + logicalTtl.toMillis()
            ));
            stringRedisTemplate.opsForValue().set(key, payload, withJitter(physicalTtl));
        } catch (Exception ex) {
            log.warn("Redis 写入诊断缓存失败，降级到本地缓存: key={}", key, ex);
            fallbackAnswerCache.put(key, new CacheEntry(
                    answer,
                    now + physicalTtl.toMillis(),
                    now + logicalTtl.toMillis(),
                    empty
            ));
        }
    }

    private AnswerCachePayload deserializePayload(String payload, long now) {
        if (payload.isBlank()) {
            return new AnswerCachePayload("", true, now, now + RedisKeySchema.ANSWER_NULL_TTL.toMillis());
        }
        try {
            return objectMapper.readValue(payload, AnswerCachePayload.class);
        } catch (JsonProcessingException ex) {
            // 兼容旧版“直接存字符串”的答案缓存。
            return new AnswerCachePayload(payload, false, now, now + RedisKeySchema.ANSWER_CACHE_TTL.toMillis());
        }
    }

    private AnswerCacheLookup toLookup(String key, CacheEntry entry, long now) {
        if (entry.empty()) {
            return new AnswerCacheLookup(entry.answer(), CacheLookupState.EMPTY, key);
        }
        if (entry.logicalExpired(now)) {
            return new AnswerCacheLookup(entry.answer(), CacheLookupState.STALE, key);
        }
        return new AnswerCacheLookup(entry.answer(), CacheLookupState.FRESH, key);
    }

    private void trackHotAnswerKey(String cacheKey) {
        try {
            stringRedisTemplate.opsForZSet().incrementScore(RedisKeySchema.answerHotKeyZset(), cacheKey, 1.0d);
        } catch (Exception ex) {
            log.debug("Redis 记录热点答案 key 失败: key={}", cacheKey, ex);
        }
    }

    private long countKeys(String pattern) {
        try {
            Set<String> keys = stringRedisTemplate.keys(pattern);
            return keys == null ? 0L : keys.size();
        } catch (Exception ex) {
            log.warn("Redis key 统计失败: pattern={}", pattern, ex);
            return 0L;
        }
    }

    private Duration withJitter(Duration baseTtl) {
        long baseMillis = baseTtl.toMillis();
        long jitterMillis = ThreadLocalRandom.current().nextLong(Math.max(1L, baseMillis / 10));
        return Duration.ofMillis(baseMillis + jitterMillis);
    }

    private boolean fallbackShouldContinue(String sessionId) {
        Long expireAt = fallbackGeneratingSessions.get(sessionId);
        if (expireAt == null) {
            return false;
        }
        if (expireAt < System.currentTimeMillis()) {
            fallbackGeneratingSessions.remove(sessionId);
            return false;
        }
        return true;
    }

    private long fallbackRemainingTime(String sessionId) {
        Long expireAt = fallbackGeneratingSessions.get(sessionId);
        if (expireAt == null) {
            return 0L;
        }
        long remaining = Math.max(0L, expireAt - System.currentTimeMillis());
        if (remaining == 0L) {
            fallbackGeneratingSessions.remove(sessionId);
        }
        return remaining;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public enum CacheLookupState {
        MISS,
        FRESH,
        STALE,
        EMPTY
    }

    public record AnswerCacheLookup(String answer, CacheLookupState state, String cacheKey) {
        public static AnswerCacheLookup miss(String cacheKey) {
            return new AnswerCacheLookup(null, CacheLookupState.MISS, cacheKey);
        }

        public boolean hit() {
            return state == CacheLookupState.FRESH || state == CacheLookupState.STALE;
        }

        public boolean stale() {
            return state == CacheLookupState.STALE;
        }

        public String cacheableAnswer() {
            return hit() ? answer : null;
        }
    }

    public record HotAnswerKeyStat(String cacheKey, double score) {
    }

    public record WarmupResult(long requested, long warmed, long missing, long failed) {
    }

    public record ClearAnswerCacheResult(
            long deletedAnswerKeys,
            long deletedRebuildLocks,
            boolean deletedHotKeyIndex,
            boolean fallbackOnly
    ) {
    }

    private record AnswerCachePayload(String answer, boolean empty, long cachedAt, long logicalExpireAt) {
    }

    private record CacheEntry(String answer, long localExpireAt, long logicalExpireAt, boolean empty) {
        private boolean localExpired(long now) {
            return localExpireAt < now;
        }

        private boolean logicalExpired(long now) {
            return logicalExpireAt < now;
        }
    }
}
