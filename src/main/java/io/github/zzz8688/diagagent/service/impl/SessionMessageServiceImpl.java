package io.github.zzz8688.diagagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.zzz8688.diagagent.entity.SessionMessage;
import io.github.zzz8688.diagagent.mapper.SessionMessageMapper;
import io.github.zzz8688.diagagent.service.SessionMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class SessionMessageServiceImpl extends ServiceImpl<SessionMessageMapper, SessionMessage>
        implements SessionMessageService {

    @Override
    public void saveMessage(String sessionId, String role, String content) {
        log.info("保存会话消息：sessionId={}, role={}", sessionId, role);

        SessionMessage message = SessionMessage.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();

        save(message);

        log.info("会话消息保存成功，ID: {}", message.getId());
    }

    @Override
    public List<SessionMessage> getMessagesBySessionId(String sessionId) {
        log.info("获取会话消息：sessionId={}", sessionId);

        LambdaQueryWrapper<SessionMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SessionMessage::getSessionId, sessionId)
               .orderByAsc(SessionMessage::getCreatedAt);

        return list(wrapper);
    }

    @Override
    public void deleteMessagesBySessionId(String sessionId) {
        log.info("删除会话消息：sessionId={}", sessionId);

        LambdaQueryWrapper<SessionMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SessionMessage::getSessionId, sessionId);

        remove(wrapper);

        log.info("会话消息删除成功");
    }
}
