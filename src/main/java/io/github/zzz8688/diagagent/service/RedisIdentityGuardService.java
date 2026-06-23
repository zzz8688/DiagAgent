package io.github.zzz8688.diagagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisIdentityGuardService {

    private final StringRedisTemplate stringRedisTemplate;

    public void markSessionId(String sessionId) {
        markBloom(RedisKeySchema.bloomSession(), RedisKeySchema.SESSION_BLOOM_BITS, sessionId);
        clearNullCache(RedisKeySchema.nullSessionCache(sessionId));
    }

    public void markTaskId(String taskId) {
        markBloom(RedisKeySchema.bloomTask(), RedisKeySchema.TASK_BLOOM_BITS, taskId);
        clearNullCache(RedisKeySchema.nullTaskCache(taskId));
    }

    public boolean mightContainSessionId(String sessionId) {
        return mightContain(RedisKeySchema.bloomSession(), RedisKeySchema.SESSION_BLOOM_BITS, sessionId);
    }

    public boolean mightContainTaskId(String taskId) {
        return mightContain(RedisKeySchema.bloomTask(), RedisKeySchema.TASK_BLOOM_BITS, taskId);
    }

    public boolean isSessionNullCached(String sessionId) {
        return isNullCached(RedisKeySchema.nullSessionCache(sessionId));
    }

    public boolean isTaskNullCached(String taskId) {
        return isNullCached(RedisKeySchema.nullTaskCache(taskId));
    }

    public void cacheMissingSession(String sessionId) {
        cacheMissing(RedisKeySchema.nullSessionCache(sessionId));
    }

    public void cacheMissingTask(String taskId) {
        cacheMissing(RedisKeySchema.nullTaskCache(taskId));
    }

    public long estimateSessionBloomBits() {
        return countSetBits(RedisKeySchema.bloomSession(), RedisKeySchema.SESSION_BLOOM_BITS);
    }

    public long estimateTaskBloomBits() {
        return countSetBits(RedisKeySchema.bloomTask(), RedisKeySchema.TASK_BLOOM_BITS);
    }

    public long countSessionNullCacheKeys() {
        return countKeys("diag:null:session:*");
    }

    public long countTaskNullCacheKeys() {
        return countKeys("diag:null:task:*");
    }

    private void markBloom(String redisKey, long bits, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            for (long offset : offsets(value, bits)) {
                stringRedisTemplate.opsForValue().setBit(redisKey, offset, true);
            }
        } catch (Exception ex) {
            log.warn("Redis bloom 写入失败: key={}, value={}", redisKey, value, ex);
        }
    }

    private boolean mightContain(String redisKey, long bits, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            for (long offset : offsets(value, bits)) {
                Boolean bit = stringRedisTemplate.opsForValue().getBit(redisKey, offset);
                if (!Boolean.TRUE.equals(bit)) {
                    return false;
                }
            }
            return true;
        } catch (Exception ex) {
            log.warn("Redis bloom 读取失败，降级为放行查询: key={}, value={}", redisKey, value, ex);
            return true;
        }
    }

    private void cacheMissing(String redisKey) {
        try {
            stringRedisTemplate.opsForValue().set(redisKey, "1", RedisKeySchema.NULL_CACHE_TTL);
        } catch (Exception ex) {
            log.warn("Redis null cache 写入失败: key={}", redisKey, ex);
        }
    }

    private boolean isNullCached(String redisKey) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(redisKey));
        } catch (Exception ex) {
            log.warn("Redis null cache 读取失败: key={}", redisKey, ex);
            return false;
        }
    }

    private void clearNullCache(String redisKey) {
        try {
            stringRedisTemplate.delete(redisKey);
        } catch (Exception ex) {
            log.debug("Redis null cache 清理失败: key={}", redisKey, ex);
        }
    }

    private long countSetBits(String redisKey, long upperBound) {
        long setBits = 0L;
        long sampleWindow = Math.min(4096L, upperBound);
        for (long i = 0; i < sampleWindow; i++) {
            try {
                Boolean bit = stringRedisTemplate.opsForValue().getBit(redisKey, i);
                if (Boolean.TRUE.equals(bit)) {
                    setBits++;
                }
            } catch (Exception ex) {
                log.warn("Redis bloom 采样失败: key={}", redisKey, ex);
                return 0L;
            }
        }
        return setBits;
    }

    private long countKeys(String pattern) {
        try {
            var keys = stringRedisTemplate.keys(pattern);
            return keys == null ? 0L : keys.size();
        } catch (Exception ex) {
            log.warn("Redis key 统计失败: pattern={}", pattern, ex);
            return 0L;
        }
    }

    private List<Long> offsets(String value, long bits) {
        byte[] digest = digest(value);
        long hash1 = readLong(digest, 0);
        long hash2 = readLong(digest, 8);
        if (hash2 == 0L) {
            hash2 = 0x9e3779b97f4a7c15L;
        }
        List<Long> offsets = new ArrayList<>(RedisKeySchema.BLOOM_HASH_COUNT);
        for (int i = 0; i < RedisKeySchema.BLOOM_HASH_COUNT; i++) {
            long combined = hash1 + (long) i * hash2;
            offsets.add(Math.floorMod(combined, bits));
        }
        return offsets;
    }

    private byte[] digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("unable to calculate bloom digest", ex);
        }
    }

    private long readLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int i = 0; i < 8 && offset + i < bytes.length; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xffL);
        }
        return value;
    }
}
