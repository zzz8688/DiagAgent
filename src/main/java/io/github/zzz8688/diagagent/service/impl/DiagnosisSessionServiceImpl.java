package io.github.zzz8688.diagagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.zzz8688.diagagent.entity.DiagnosisSession;
import io.github.zzz8688.diagagent.mapper.DiagnosisSessionMapper;
import io.github.zzz8688.diagagent.service.DiagnosisSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class DiagnosisSessionServiceImpl extends ServiceImpl<DiagnosisSessionMapper, DiagnosisSession> 
        implements DiagnosisSessionService {

    @Override
    public void createSession(DiagnosisSession session) {
        log.info("创建会话: {}", session.getSessionId());
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        save(session);
    }

    @Override
    public List<DiagnosisSession> getAllSessions() {
        log.debug("获取所有会话");
        LambdaQueryWrapper<DiagnosisSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(DiagnosisSession::getUpdatedAt);
        return list(wrapper);
    }

    @Override
    public DiagnosisSession getSessionById(String sessionId) {
        log.debug("获取会话: {}", sessionId);
        LambdaQueryWrapper<DiagnosisSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiagnosisSession::getSessionId, sessionId);
        return getOne(wrapper);
    }

    @Override
    public void updateSession(DiagnosisSession session) {
        log.debug("更新会话: {}", session.getSessionId());
        session.setUpdatedAt(LocalDateTime.now());
        updateById(session);
    }

    @Override
    public void deleteSession(String sessionId) {
        log.info("删除会话: {}", sessionId);
        LambdaQueryWrapper<DiagnosisSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DiagnosisSession::getSessionId, sessionId);
        remove(wrapper);
    }

    @Override
    public void deleteAllSessions() {
        log.info("删除所有会话");
        remove(null);
    }
}
