package io.github.zzz8688.diagagent.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Service
public class RedisDistributedLockService {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("scripts/redis-unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public RedisDistributedLockService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public String tryLock(String lockKey, Duration ttl) {
        String token = UUID.randomUUID() + "-" + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, token, ttl);
        return Boolean.TRUE.equals(success) ? token : null;
    }

    public boolean unlock(String lockKey, String token) {
        if (lockKey == null || lockKey.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        Long result = stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(lockKey),
                token
        );
        return result != null && result > 0;
    }
}
