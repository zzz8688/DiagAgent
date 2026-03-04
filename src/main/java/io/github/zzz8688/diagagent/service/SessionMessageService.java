package io.github.zzz8688.diagagent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import io.github.zzz8688.diagagent.entity.SessionMessage;

import java.util.List;

public interface SessionMessageService extends IService<SessionMessage> {

    void saveMessage(String sessionId, String role, String content);

    List<SessionMessage> getMessagesBySessionId(String sessionId);

    void deleteMessagesBySessionId(String sessionId);
}
