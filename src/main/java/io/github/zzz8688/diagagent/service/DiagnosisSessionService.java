package io.github.zzz8688.diagagent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import io.github.zzz8688.diagagent.entity.DiagnosisSession;

import java.util.List;

public interface DiagnosisSessionService extends IService<DiagnosisSession> {

    void createSession(DiagnosisSession session);

    List<DiagnosisSession> getAllSessions();

    DiagnosisSession getSessionById(String sessionId);

    void updateSession(DiagnosisSession session);

    void deleteSession(String sessionId);

    void deleteAllSessions();
}
