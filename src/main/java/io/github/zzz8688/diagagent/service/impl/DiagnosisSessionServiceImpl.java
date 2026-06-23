package io.github.zzz8688.diagagent.service.impl;

import io.github.zzz8688.diagagent.entity.DiagnosisSession;
import io.github.zzz8688.diagagent.mapper.DiagnosisSessionMapper;
import io.github.zzz8688.diagagent.service.DiagnosisSessionService;
import io.github.zzz8688.diagagent.service.RedisIdentityGuardService;
import io.github.zzz8688.diagagent.util.JwtContextUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DiagnosisSessionServiceImpl implements DiagnosisSessionService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisSessionServiceImpl.class);

    private final DiagnosisSessionMapper diagnosisSessionMapper;
    private final RedisIdentityGuardService redisIdentityGuardService;

    @Override
    public void createSession(DiagnosisSession session) {
        log.info("创建或更新会话: {}", session.getSessionId());
        
        // 检查会话是否已存在
        DiagnosisSession existingSession = getSessionById(session.getSessionId());
        
        Long userId = JwtContextUtil.getCurrentUserId();
        
        if (existingSession != null) {
            log.info("会话已存在，执行更新");
            // 更新现有会话
            existingSession.setEngine(session.getEngine());
            existingSession.setUpdatedAt(LocalDateTime.now());
            updateSession(existingSession);
            redisIdentityGuardService.markSessionId(existingSession.getSessionId());
        } else {
            log.info("会话不存在，创建新会话");
            // 创建新会话
            if (userId != null) {
                session.setUserId(userId);
                log.info("会话关联用户: {}", userId);
            }
            session.setCreatedAt(LocalDateTime.now());
            session.setUpdatedAt(LocalDateTime.now());
            diagnosisSessionMapper.insert(session);
            redisIdentityGuardService.markSessionId(session.getSessionId());
        }
    }

    @Override
    public List<DiagnosisSession> getAllSessions() {
        log.debug("获取所有会话");
        Long userId = JwtContextUtil.getCurrentUserId();
        if (userId != null) {
            log.info("获取用户 {} 的会话", userId);
        }
        List<DiagnosisSession> sessions = diagnosisSessionMapper.findAllByUserId(userId);
        sessions.forEach(session -> redisIdentityGuardService.markSessionId(session.getSessionId()));
        return sessions;
    }

    @Override
    public DiagnosisSession getSessionById(String sessionId) {
        log.debug("获取会话: {}", sessionId);
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (redisIdentityGuardService.isSessionNullCached(sessionId)) {
            return null;
        }
        DiagnosisSession session = diagnosisSessionMapper.findBySessionId(sessionId);
        if (session == null) {
            redisIdentityGuardService.cacheMissingSession(sessionId);
            return null;
        }
        redisIdentityGuardService.markSessionId(sessionId);
        return session;
    }

    @Override
    public void updateSession(DiagnosisSession session) {
        log.debug("更新会话: {}", session.getSessionId());
        session.setUpdatedAt(LocalDateTime.now());
        diagnosisSessionMapper.updateById(session);
    }

    @Override
    public void deleteSession(String sessionId) {
        log.info("删除会话: {}", sessionId);
        diagnosisSessionMapper.deleteBySessionId(sessionId);
    }

    @Override
    public void deleteAllSessions() {
        log.info("删除所有会话");
        Long userId = JwtContextUtil.getCurrentUserId();
        diagnosisSessionMapper.deleteAllByUserId(userId);
    }
}
