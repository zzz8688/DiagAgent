package io.github.zzz8688.diagagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisRuntimeCoordinationService {

    private static final DateTimeFormatter RATE_BUCKET_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
            .withZone(ZoneOffset.UTC);

    private final RedisDistributedLockService redisDistributedLockService;
    private final StringRedisTemplate stringRedisTemplate;

    public String tryAcquireSessionExecutionLock(String sessionId, String requestMode) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return redisDistributedLockService.tryLock(
                    RedisKeySchema.sessionExecutionLock(sessionId),
                    RedisKeySchema.SESSION_EXECUTION_LOCK_TTL
            );
        } catch (Exception ex) {
            log.warn("获取 session 主锁失败: sessionId={}, requestMode={}", sessionId, requestMode, ex);
            return null;
        }
    }

    public boolean releaseSessionExecutionLock(String sessionId, String token) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return redisDistributedLockService.unlock(RedisKeySchema.sessionExecutionLock(sessionId), token);
    }

    public String tryAcquireTaskLifecycleLock(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        try {
            return redisDistributedLockService.tryLock(
                    RedisKeySchema.taskLifecycleLock(taskId),
                    RedisKeySchema.TASK_LIFECYCLE_LOCK_TTL
            );
        } catch (Exception ex) {
            log.warn("获取 task 生命周期锁失败: taskId={}", taskId, ex);
            return null;
        }
    }

    public boolean releaseTaskLifecycleLock(String taskId, String token) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        return redisDistributedLockService.unlock(RedisKeySchema.taskLifecycleLock(taskId), token);
    }

    public RateLimitDecision checkSessionRateLimit(String sessionId, String scope, long maxRequests) {
        String identity = sessionId == null || sessionId.isBlank() ? "anonymous" : sessionId.trim();
        String bucket = RATE_BUCKET_FORMATTER.format(Instant.now());
        String redisKey = RedisKeySchema.rateLimit(scope, identity, bucket);
        try {
            Long count = stringRedisTemplate.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(redisKey, RedisKeySchema.RATE_LIMIT_WINDOW.plusSeconds(5));
            }
            long current = count == null ? 0L : count;
            return new RateLimitDecision(current <= maxRequests, current, maxRequests, redisKey);
        } catch (Exception ex) {
            log.warn("Redis 限流检查失败，降级为放行: sessionId={}, scope={}", sessionId, scope, ex);
            return new RateLimitDecision(true, 0L, maxRequests, redisKey);
        }
    }

    public long countActiveSessionLocks() {
        return countKeys("diag:lock:session:*");
    }

    public long countActiveTaskLocks() {
        return countKeys("diag:lock:task:*");
    }

    public long countRateLimitKeys() {
        return countKeys("diag:rate:*");
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

    public record RateLimitDecision(boolean allowed, long currentCount, long maxRequests, String rateKey) {
    }
}
